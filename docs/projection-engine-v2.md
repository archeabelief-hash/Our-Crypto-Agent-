# Projection Engine V2 — Real Market Behavior Upgrade

## Purpose
Upgrade the current heuristic projection model so it reacts to live market microstructure instead of relying only on static interval movement assumptions.

The current model is strong at fee/friction filtering. V2 improves prediction quality by adding behavior-aware signals:

- imbalance momentum
- spread-spike halt
- volatility-adjusted thresholds
- hollow-book detection
- order-book wall quality
- maker-first execution bias
- live slippage safety buffer

## Current Projection Formula

```js
strength = min(1.8, abs(imbalance) / 35)
projectedMove = baseMove * (0.65 + strength) * liquidityFactor * scoutPenalty
```

## V2 Projection Formula

```js
projectedMoveV2 = baseMove
  * imbalanceStrength
  * liquidityFactor
  * imbalanceMomentumFactor
  * spreadHealthFactor
  * hollowBookFactor
  * wallQualityFactor
  * volatilityRegimeFactor
  * scoutPenalty
```

## 1. Imbalance Momentum

Current imbalance alone can be late. V2 tracks whether pressure is increasing or fading.

```js
imbalanceMomentum = currentImbalance - avgImbalance60s
```

Factor:

```js
if imbalanceMomentum > 8:
  factor = 1.18
else if imbalanceMomentum > 3:
  factor = 1.08
else if imbalanceMomentum < -8:
  factor = 0.72
else if imbalanceMomentum < -3:
  factor = 0.88
else:
  factor = 1.0
```

Meaning:

- High but falling imbalance is weaker.
- Medium but rising imbalance may be stronger.

## 2. Spread Spike Halt

If spread suddenly expands, the book is unstable.

```js
spreadSpikeRatio = currentSpreadPct / avgSpread60s
```

Gate:

```js
if spreadSpikeRatio >= 2.0:
  status = HALT_SPREAD_SPIKE
```

Factor:

```js
if spreadSpikeRatio > 1.5:
  factor = 0.65
else if spreadSpikeRatio > 1.2:
  factor = 0.82
else:
  factor = 1.0
```

## 3. Volatility-Adjusted Thresholds

Scout and confirmation imbalance thresholds should rise when live volatility increases.

```js
volatilityRatio = currentMicroVolatility / avgMicroVolatility60s
```

Threshold:

```js
baseScoutThreshold = 14
baseConfirmThreshold = 8
adjustedThreshold = baseThreshold * clamp(volatilityRatio, 0.85, 1.75)
```

Result:

- Sideways market: easier to pass.
- Fast market/crash: harder to pass.

## 4. Hollow Book Penalty

Visible depth can be misleading if liquidity is far away from the top of book.

V2 compares top-5 depth to top-35 depth:

```js
top5Share = depth(top 5) / depth(top 35)
```

Factor:

```js
if top5Share < 0.12:
  factor = 0.55
else if top5Share < 0.22:
  factor = 0.75
else if top5Share < 0.35:
  factor = 0.90
else:
  factor = 1.0
```

Meaning:

- Liquidity concentrated far away is lower quality.
- Top-heavy books are better for fills.

## 5. Wall Quality Factor

V2 penalizes walls that just appeared and rewards persistent walls.

Frontend approximation:

```js
wallPersistence = timesSeenAtSimilarPrice / maxSamples
```

Factor:

```js
if wallPersistence > 0.70:
  factor = 1.12
else if wallPersistence > 0.40:
  factor = 1.04
else if wallPersistence < 0.15:
  factor = 0.82
else:
  factor = 1.0
```

## 6. Maker-First Bias

The execution model should avoid taker entries unless price is escaping.

V2 auto fee mode logic:

```js
if spread is tight and imbalance is rising:
  prefer maker entry

if price is running away and projected edge is large:
  allow taker entry

otherwise:
  maker entry
```

Rule:

```js
if spreadPct < 0.04 and imbalanceMomentum > 0:
  entry = maker
else if abs(imbalance) > 48 and imbalanceMomentum > 8:
  entry = taker
else:
  entry = maker
```

## 7. Live Slippage Safety Buffer

The old required-move buffer is fixed:

```js
buffer = 0.15%
```

V2 uses adaptive buffer:

```js
buffer = 0.15%
       + spreadPct * 0.35
       + max(0, spreadSpikeRatio - 1) * 0.05%
       + volatilityPenalty
```

## 8. V2 READY Gate

A signal is READY only if:

```js
projectedMoveV2 > requiredMoveV2
actualPnL > 0
liquidityPnlEstimate > 0
spreadSpikeRatio < 2.0
abs(imbalance) > adjustedThreshold
hollowBookFactor >= 0.75
```

## 9. V2 Score

```js
scoreV2 = oldScore
  + imbalanceMomentumBonus
  + wallQualityBonus
  - spreadSpikePenalty
  - hollowBookPenalty
  - volatilityPenalty
```

## 10. Practical Result

V2 should:

- reject more fake 1M/5M signals
- avoid spread-spike entries
- favor rising pressure over stale pressure
- avoid hollow books
- prefer maker entries unless urgency is justified
- improve paper-tracker quality before any future execution system

## Implementation Plan

1. Add isolated V2 page first.
2. Verify behavior against current Live Decision.
3. Compare paper-tracker results for V1 vs V2.
4. Only then promote V2 logic into production pages.

## Status
Specification added. Implementation should be isolated first as:

```text
/dev/live-decision-v2.html
```

or:

```text
live-decision-v2.html
```
