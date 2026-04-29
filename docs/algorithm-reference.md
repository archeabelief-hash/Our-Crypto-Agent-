# Crypto AI Algorithm Reference

This document explains the current prediction, signal, PnL, sizing, and tracker logic used across the app.

## 1. Core Market Data

The app reads Coinbase public order book data:

```text
https://api.exchange.coinbase.com/products/{PAIR}/book?level=2
```

Used values:

- best bid
- best ask
- top visible bid depth
- top visible ask depth
- order size repetition
- local average order notional
- spread
- visible liquidity

The app does not currently use private account data or real order execution in the frontend.

---

## 2. Order Book Depth

Depth is calculated by summing visible notional value:

```js
depth(levels, n = 35) = sum(price * size) for top n levels
```

Bid depth and ask depth are compared to determine pressure.

---

## 3. Book Imbalance

The directional pressure score is:

```js
imbalance = ((bidDepth - askDepth) / (bidDepth + askDepth)) * 100
```

Meaning:

- Positive imbalance = buy-side pressure, engine side becomes LONG.
- Negative imbalance = sell-side pressure, engine side becomes SHORT.

For spot-only trading:

- LONG means possible BUY.
- SHORT means SELL/EXIT if already holding, or STAY OUT if not holding.

---

## 4. Wall / Bot / Spoof Detection

For each visible book level, the app computes:

```js
notional = price * size
multiple = notional / localAverageNotional
repeatedSizeHits = count of similar order sizes in top book
```

Classification rules:

```text
If multiple >= 3.2 and level index <= 8:
  BUY WALL or SELL WALL

Else if multiple >= 2.1:
  WATCH WALL

If multiple >= 2.6 and index > 8:
  SPOOF WATCH

If repeatedSizeHits >= 3 and multiple < 3.2:
  BOT LADDER
```

Purpose:

- BUY WALL = likely support rail.
- SELL WALL = likely resistance rail.
- WATCH WALL = meaningful liquidity but not strong enough.
- SPOOF WATCH = large liquidity deeper in book, possible bait.
- BOT LADDER = repeated size behavior, possible algorithmic stepping.

---

## 5. Dynamic Coinbase Fee Model

The fee tiers used by the app are:

```js
0-10K:      maker 0.40%, taker 0.60%
10-50K:     maker 0.25%, taker 0.40%
50-100K:    maker 0.15%, taker 0.25%
100K-1M:    maker 0.10%, taker 0.20%
1M-15M:     maker 0.08%, taker 0.18%
15M-75M:    maker 0.06%, taker 0.16%
75M-250M:   maker 0.03%, taker 0.12%
250M-400M:  maker 0.00%, taker 0.08%
400M+:      maker 0.00%, taker 0.05%
```

Fee modes:

```text
Taker/Taker = market-style entry and exit
Maker/Taker = limit entry, urgent exit
Maker/Maker = limit entry and limit exit
Auto = app decides based on imbalance and spread
```

Auto mode logic:

```js
if abs(imbalance) > 40 or spread > 0.12%:
  entry = taker
else:
  entry = maker

if abs(imbalance) > 28 or spread > 0.08%:
  exit = taker
else:
  exit = maker
```

---

## 6. Multi-Timeframe Projection Engine

The app currently uses estimated base move values by interval:

```js
1M scout: 0.15%
5M:       0.35%
15M:      0.75%
1H:       1.25%
1D:       2.75%
1W:       5.50%
1Month:   9.00%
```

Projection formula:

```js
strength = min(1.8, abs(imbalance) / 35)
liquidityFactor = liquidity/spread/coin adjustment
scoutPenalty = 0.82 for 1M and 5M, else 1

projectedMove = baseMove * (0.65 + strength) * liquidityFactor * scoutPenalty
```

Interpretation:

- 1M and 5M are scout intervals.
- 15M and 1H are confirmation intervals.
- 1D, 1W, 1Mth are broader swing/macro intervals.

---

## 7. Liquidity Factor

Liquidity factor modifies the projected move:

```js
baseFactor = 1

if coin is BTC or ETH:
  factor *= 1.12

if coin is DOGE or SHIB:
  factor *= 0.82

if visible depth < $5,000:
  factor *= 0.65
else if depth < $25,000:
  factor *= 0.82
else if depth > $250,000:
  factor *= 1.15

if spread > 0.15%:
  factor *= 0.65
else if spread > 0.05%:
  factor *= 0.82

final factor is clamped between 0.35 and 1.35
```

Purpose:

- Penalize thin books.
- Penalize wide spreads.
- Boost highly liquid books.

---

## 8. Required Move Calculation

For each interval, the app first simulates a probe trade and estimates friction:

```js
friction = fees + slippage + spreadDrag + bookImpact
requiredMove = friction / amount + buffer
buffer = 0.15%
```

A trade cannot be READY unless:

```js
projectedMove > requiredMove
```

---

## 9. Target and Stop Logic

The app expands the final target so it clears costs:

```js
finalMove = max(requiredMove * 1.18, projectedMove)
stopMove = max(0.15%, finalMove * 0.52)
```

For LONG:

```js
entry = ask
target = entry * (1 + finalMove)
stop = entry * (1 - stopMove)
```

For SHORT analysis:

```js
entry = bid
target = entry * (1 - finalMove)
stop = entry * (1 + stopMove)
```

Spot tracker only opens LONG.

---

## 10. Actual PnL Estimate

The live decision engine estimates actual PnL by consuming visible book liquidity.

Entry fill:

```js
LONG entry consumes asks
SHORT entry consumes bids
```

Exit fill:

```js
LONG exit consumes bids
SHORT exit consumes asks
```

Costs included:

- entry fee
- exit fee
- entry slippage
- exit slippage
- spread drag
- entry book impact
- exit book impact

Formula:

```js
gross = targetGrossMove
friction = fees + slippage + spreadDrag + impact
actualPnL = gross - friction
```

Stop risk uses the same model with stop price.

---

## 11. READY / NO SETUP Gate

A row becomes READY only if all are true:

```js
pnl.actual > 0
projectedMove > requiredMove
abs(imbalance) > threshold
```

Threshold:

```text
1M / 5M scout: abs(imbalance) > 14
15M+ confirm:  abs(imbalance) > 8
```

Otherwise:

```text
NO SETUP
```

---

## 12. Score / Confidence

Score is estimated as:

```js
score = abs(imbalance)
      + 20
      + (projectedMove - requiredMove) * 2200
      + (actualPnL / amount) * 2500
      - scoutPenalty
```

Then clamped:

```js
score = min(100, max(0, score))
```

Scout penalty:

```text
1M and 5M lose 8 points
```

---

## 13. Best Setup Selection

All intervals are scanned:

```text
1M scout
5M
15M
1H
1D
1W
1Mth
```

Best valid setup is chosen by:

```js
validPlans = plans where clear == true
sort by score descending, then pnl.actual descending
best = first valid plan
```

If no valid plan exists:

```js
best = highest score plan but decision remains NO SETUP
```

---

## 14. Liquidity-Based PnL Engine

The dedicated Liquidity PnL page performs a size sweep:

```js
AMOUNTS = [5,10,20,40,75,100,150,250,500,750,1000,1500,2500,5000]
```

For each amount:

1. Buy amount across ask levels.
2. Compute entry VWAP.
3. Shift bid book up by target percent.
4. Sell token quantity across shifted bids.
5. Compute exit VWAP.
6. Shift bid book down by stop percent.
7. Compute stop exit VWAP.
8. Subtract fees, slippage, and impact.

Core formula:

```js
entry = fillBuy(asks, amount)
exit = fillSellByQty(shiftedBidsUp, entry.qty)
stop = fillSellByQty(shiftedBidsDown, entry.qty)

gross = exit.proceeds - entry.cost
fees = entry.cost * entryFee + exit.proceeds * exitFee
slippage = (entry.cost + exit.proceeds) * extraSlip
impact = entryImpact + exitImpact
net = gross - fees - slippage - impact
```

The best amount is:

```js
profitable rows sorted by highest net PnL
```

If none are profitable, the least-bad row is shown.

---

## 15. Spot Long Paper Tracker Logic

The tracker is spot-only.

It opens paper trades only if:

```js
plan.clear == true
plan.side == 'LONG'
timeframe is enabled: 1M, 5M, 15M, or 1H
```

It ignores:

```text
READY SHORT rows
```

Open trade sizing:

```js
qty = paperBuyAmount / entry
entryFee = paperBuyAmount * entryFeeRate
```

Close logic for spot LONG:

```js
if liveBid >= target:
  close as TARGET

if liveBid <= stop:
  close as STOP
```

Final paper PnL:

```js
gross = (exitPrice - entryPrice) * qty
exitFee = qty * exitPrice * exitFeeRate
netPnL = gross - entryFee - exitFee
```

The tracker also records elapsed waiting time using `openedAt`.

---

## 16. Current Limitations

These are important:

1. Projection is heuristic, not ML-trained.
2. The app reads visible order book only, not hidden liquidity.
3. No candle OHLCV indicator confirmation is currently used.
4. No RSI/MACD/VWAP indicator parity yet.
5. No true historical backtest engine yet.
6. Frontend math uses JavaScript Number, not Decimal.
7. Paper tracking is local browser storage only.
8. Live Coinbase order execution is not enabled.

---

## 17. Practical Meaning

The algorithm is best understood as:

```text
Order book pressure + liquidity + fees + size impact + interval projection
```

It is not trying to predict the future perfectly. It is trying to answer:

```text
Does this setup have enough projected movement to clear all trading friction?
```

If yes:

```text
READY
```

If not:

```text
NO SETUP
```

---

## 18. What To Improve Next

Highest impact upgrades:

1. Move shared logic into one reusable engine module.
2. Add true OHLCV candles for each interval.
3. Add VWAP, RSI, MACD, ATR, and volume confirmation.
4. Add historical paper-backtest storage.
5. Connect liquidity sizing to Live Decision and Tracker.
6. Convert backend agent to Decimal math.
7. Add timeout scoring by interval.
8. Track false positives and missed trades to refine thresholds.
