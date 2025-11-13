import discord
from discord.ext import commands
import requests
import asyncio
import os
import time
import hmac
import hashlib
from dotenv import load_dotenv

# ============================================================
# 1) 로컬 / 운영 자동 감지
# ============================================================

ENV_LOCAL_PATH = os.path.join(os.path.dirname(__file__), ".env.local")
IS_LOCAL = os.path.exists(ENV_LOCAL_PATH)

if IS_LOCAL:
    load_dotenv(ENV_LOCAL_PATH)
    print("🚀 [LOCAL MODE] .env.local 파일 로드됨!")
else:
    print("🌐 [PROD MODE] Docker Secret 기반 동작")

# ============================================================
# 2) 시크릿 로더 (운영 모드에서 secret 파일 읽기)
# ============================================================

def load_secret(env_var_name_for_path, env_var_name_for_value):
    file_path = os.getenv(env_var_name_for_path)

    # 운영 모드 (Docker secret 파일)
    if file_path:
        try:
            with open(file_path, 'r') as f:
                return f.read().strip()
        except Exception as e:
            print(f"!!! ERROR: Docker 시크릿 파일 읽기 실패: {file_path} ({e})")
            return None
    else:
        # 로컬 모드 (환경 변수 직접)
        return os.getenv(env_var_name_for_value)


# ============================================================
# 3) 공통 환경변수 로드
# ============================================================

TOKEN = load_secret("TOKEN_FILE_PATH", "DISCORD_BOT_TOKEN")
BOT_SECRET_KEY = load_secret("BOT_ACCESS_KEY_FILE_PATH", "BOT_ACCESS_KEY")
SPRING_BOOT_API_URL = os.getenv("SPRING_BOOT_API_URL")

if TOKEN is None or BOT_SECRET_KEY is None or SPRING_BOOT_API_URL is None:
    print("❌ ERROR: 필수 환경변수 누락!")
    exit()

# ============================================================
# 4) Discord Bot 설정
# ============================================================

intents = discord.Intents.default()
intents.message_content = True
bot = commands.Bot(command_prefix="!", intents=intents)
processing_users = set()

# 🔥 기본 help 완전 비활성화
bot.help_command = None
bot.remove_command("help")

# ============================================================
# 5) 인증 헤더 생성 (HMAC)
# ============================================================

def get_auth_headers():
    nonce = str(int(time.time() * 1000))
    signature = hmac.new(
        BOT_SECRET_KEY.encode(),
        nonce.encode(),
        hashlib.sha256
    ).hexdigest()

    return {
        "X-Bot-Nonce": nonce,
        "X-Bot-Signature": signature
    }

# ============================================================
# 6) 입력 파서 (exchange / action / market / state)
# ============================================================

def parse_query(query_string):
    query = query_string.lower().split()

    exchange = action = market = state = None

    # 거래소
    if "게이트아이오" in query or "gateio" in query or "1" in query:
        exchange = "gateio"
    elif "빗썸" in query or "bithumb" in query or "3" in query:
        exchange = "bithumb"
    elif "전체" in query or "all" in query or "0" in query:
        exchange = "all"

    # 기능
    if "자산" in query:
        action = "assets"
    elif "거래내역" in query or "거래" in query:
        action = "trades"
    elif "수익" in query or "pnl" in query:
        action = "pnl"

    # 마켓 KRW-BTC
    for token in query:
        if "-" in token and len(token) <= 10:
            market = token.upper()

    # 상태
    for token in query:
        if token in ["wait", "done", "cancel"]:
            state = token

    return exchange, action, market, state

# ============================================================
# 7) 봇 준비 이벤트
# ============================================================

@bot.event
async def on_ready():
    print(f"🤖 {bot.user.name} 준비 완료!")
    print(f"API 서버: {SPRING_BOOT_API_URL}")
    print(f"MODE: {'LOCAL' if IS_LOCAL else 'PROD'}")
    print("------")

# ============================================================
# 8) !조회 명령어
# ============================================================

@bot.command(name="조회")
async def unified_query(ctx, *, query_string: str = None):
    user_id = ctx.author.id

    # 도움말 모드
    if query_string is None:
        await ctx.send(
            "**Crypto Bot 명령어 도움말**\n"
            "`!조회 [거래소] [기능] [옵션]`\n\n"
            "**[거래소]**\n"
            "`0` 또는 `전체`: 모든 거래소\n"
            "`1` 또는 `게이트아이오`: Gate.io\n"
            "`3` 또는 `빗썸`: Bithumb\n\n"
            "**[기능]**\n"
            "`자산`: (!조회 0 자산)\n"
            "`거래내역`: (!조회 3 거래내역 KRW-BTC done)\n\n"
            "**[옵션]**\n"
            "`KRW-BTC`, `BTC-KRW` (마켓)\n"
            "`wait`, `done`, `cancel` (상태)\n"
        )
        return

    # 중복 요청 방지
    if user_id in processing_users:
        return
    processing_users.add(user_id)

    exchange, action, market, state = parse_query(query_string)

    if not exchange or not action:
        await ctx.send("명령어가 올바르지 않습니다. `!조회` 를 입력하세요.")
        processing_users.discard(user_id)
        return

    loading = await ctx.send(f"요청 처리 중... (`{exchange}` | `{action}`)")

    try:
        if action == "assets":
            await handle_assets(ctx, loading, user_id, exchange)

        elif action == "trades":
            await handle_trades(ctx, loading, user_id, exchange, market, state)

    except Exception as e:
        await loading.edit(content=f"❌ 오류: {e}")

    finally:
        processing_users.discard(user_id)

# ============================================================
# 9) 핸들러 구현
# ============================================================

async def handle_assets(ctx, msg, user_id, exchange):
    params = {"discord_id": str(user_id)}
    endpoint = "/my-assets" if exchange == "all" else "/assets/exchange"
    if exchange != "all":
        params["exchange"] = exchange

    response = requests.get(
        SPRING_BOOT_API_URL + endpoint,
        params=params,
        headers=get_auth_headers(),
        timeout=30
    )
    response.raise_for_status()
    data = response.json()

    embed = discord.Embed(
        title=f"{ctx.author.name}님의 자산 현황",
        color=discord.Color.green()
    )

    coins = data.get("coins", [])
    total_krw = 0
    money = 0
    point = 0

    for coin in coins:
        currency = coin.get("currency")
        balance = float(coin.get("balance", 0)) + float(coin.get("locked", 0))
        avg_buy_price = float(coin.get("avg_buy_price", 0))
        current_price = float(coin.get("current_price", 0))
        value_krw = balance * current_price
        total_krw += value_krw

        # KRW, 포인트(P)는 따로 계산
        if currency == "KRW":
            money += balance
            continue
        if currency == "P":
            point += balance
            continue

        # 수익률
        profit_percent = (
            ((current_price - avg_buy_price) / avg_buy_price * 100)
            if avg_buy_price > 0 else 0
        )
        arrow = "📈" if profit_percent >= 0 else "📉"

        embed.add_field(
            name=f"{currency} ({balance:.4f})",
            value=(
                f"평단 {avg_buy_price:,.0f} KRW | "
                f"현재 {current_price:,.0f} KRW | "
                f"총액 {value_krw:,.0f} KRW | "
                f"{arrow} {profit_percent:+.1f}%"
            ),
            inline=False
        )

    # KRW / POINT / 총합 표시
    embed.add_field(name="💰 현금", value=f"{int(money):,} KRW", inline=False)
    embed.add_field(name="💰 포인트", value=f"{int(point):,} KRW", inline=False)
    embed.add_field(
        name="💰 총 평가금액",
        value=f"{int(total_krw + money + point):,} KRW / ${total_krw / 1350:,.2f} USDT",
        inline=False
    )

    await msg.edit(content="조회 완료!", embed=embed)

async def handle_trades(ctx, msg, user_id, exchange, market, state):
    params = {
        "discord_id": str(user_id),
        "exchange": exchange
    }
    
    if market:
        params["market"] = market
    if state:
        params["state"] = state

    response = requests.get(
        SPRING_BOOT_API_URL + "/trades",
        params=params,
        headers=get_auth_headers(),
        timeout=30
    )
    response.raise_for_status()
    trades = response.json()

    # 🔥 상태 설명 변환
    STATE_MAP = {
        "wait": "체결 대기 (wait)",
        "watch": "예약주문 대기 (watch)",
        "done": "전체 체결 완료 (done)",
        "cancel": "주문 취소 (cancel)"
    }

    state_text = STATE_MAP.get(state, "전체 상태")  # 값 없으면 전체 상태

    embed = discord.Embed(
        title=f"[{exchange.upper()}] {ctx.author.name}님의 거래 내역",
        description=f"**주문 상태:** {state_text}",
        color=discord.Color.blue()
    )

    if not trades:
        embed.add_field(name="알림", value="거래 내역이 없습니다.", inline=False)
    else:
        desc = ""
        for t in trades[:10]:
            side_text = "매수" if t["side"] == "bid" else "매도"

            # 예약 주문 등 가격이 없는 경우 처리
            price = t.get("price")
            amount = t.get("amount")
            ord_type = t.get("ord_type")
            paid_fee = t.get("paid_fee")

            price_text = f"{price} KRW" if price and price != "정보 없음" else "정보 없음"
            amount_text = f"{amount} 개" if amount and amount != "정보 없음" else "정보 없음"
            order_text = "지정가" if ord_type == "limit" else "시장가"
            fee_text = f"{paid_fee} KRW" if paid_fee else "정보 없음"

            desc += (
                "```\n"
                f"- [종목] {t['symbol']}\n"
                f"= [주문 종류] {side_text}\n"
                f"+ [주문 유형] {order_text}\n"
                f"! 가격: {price_text}\n"
                f"# 수량: {amount_text}\n"
                f"; 수수료: {fee_text}\n"
                "```\n"
            )

        embed.add_field(name="최근 거래", value=desc, inline=False)

    await msg.edit(content="조회 완료!", embed=embed)

# ============================================================
# 10) !help 명령어
# ============================================================

@bot.command(name="help")
async def help_command(ctx):
    await ctx.send(
        "**Crypto Bot 명령어 도움말**\n"
        "`!조회 [거래소] [기능] [옵션]`\n\n"
        "**[거래소]**\n"
        "`0` 또는 `전체`: 모든 거래소\n"
        "`1` 또는 `게이트아이오`: Gate.io\n"
        "`3` 또는 `빗썸`: Bithumb\n\n"
        "**[기능]**\n"
        "`자산`: 자산 조회\n"
        "`거래내역`: 거래 기록 조회\n\n"
        "**[옵션]**\n"
        "`KRW-BTC`, `xrp-krw` 등 마켓\n"
        "- `wait` : 체결 대기 (default), `watch` : 예약주문 대기, `done` : 전체 체결 완료, `cancel` : 주문 취소 상태\n\n"
        "**예시**\n"
        "`!조회 빗썸 자산`\n"
        "`!조회 3 거래내역 KRW-BTC done`\n"
    )

# ============================================================
# 11) 실행
# ============================================================

print("🚀 봇 시작 중...")
bot.run(TOKEN)
