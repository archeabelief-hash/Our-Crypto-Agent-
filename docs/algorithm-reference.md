# Crypto AI Algorithm Reference (V1 + V2)

This document now includes BOTH:

- V1 (original system)
- V2 (behavior-aware projection upgrade)

---

## V2 Projection Engine (NEW)

The system now evolves from static projection to behavior-aware modeling.

### Core Upgrade

```text
OLD:
Projection based on imbalance strength only

NEW:
Projection based on REAL market behavior
```

---

## 1. Imbalance Momentum

```js
imbalanceMomentum = currentImbalance - avgImbalance60s
```

Purpose:

- Detects if pressure is increasing or fading
- Prevents entering at peak exhaustion

---

## 2. Spread Spike Detection

```js
spreadSpikeRatio = currentSpread / avgSpread60s
```

Rules:

```text
>= 2.0 → HALT ALL ENTRIES
> 1.5 → heavy penalty
> 1.2 → mild penalty
```

---

## 3. Volatility Adaptive Threshold

```js
adjustedThreshold = baseThreshold * clamp(volRatio, 0.85, 1.75)
```

Effect:

- High volatility = stricter entries
- Low volatility = more opportunity

---

## 4. Hollow Book Detection

```js
top5Depth / top35Depth
```

Penalty:

```text
< 0.12 → severe penalty
< 0.22 → moderate penalty
< 0.35 → light penalty
```

---

## 5. Wall Quality

Wall strength is now weighted by persistence and clustering.

Effect:

- Stable walls boost confidence
- Flash walls reduce confidence

---

## 6. Maker-First Execution Logic

```js
if spread tight + momentum rising:
  use maker

if breakout conditions:
  allow taker
```

Purpose:

- Reduce fees (major edge)
- Avoid unnecessary taker trades

---

## 7. Adaptive Slippage Buffer

```js
buffer = base + spreadFactor + spikeFactor + volatilityFactor
```

Effect:

- Accounts for real execution lag

---

## 8. V2 READY Gate

A trade is valid ONLY if:

```text
projectedMoveV2 > requiredMoveV2
PnL > 0
spreadSpike < 2
imbalance > dynamic threshold
hollowBook acceptable
```

---

## 9. V2 Score

Score now includes:

- imbalance momentum
- wall quality
- hollow penalties
- spread spike penalties
- volatility penalties

---

## Result of V2 Upgrade

The system now:

- avoids fake pressure entries
- avoids spread spikes
- avoids hollow books
- adapts to volatility
- favors maker entries
- improves real fill probability

---

## System Level

```text
V1 = Level 3 (Advanced)
V2 = Level 4 (Pre-Quant / Microstructure Aware)
```

---

## Next Evolution

To reach Level 5:

- Add VWAP
- Add OHLCV indicators
- Add real-time execution feedback loop
- Add historical backtesting
