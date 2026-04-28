# Project Chimera Rating Algorithm Inputs

Source: uploaded Project Chimera blueprint.

Use these principles in the live Agent rating model:

1. Edge first
- A signal must represent a quantifiable edge after fees, slippage, spread, and impact.
- If Actual PnL is not positive after friction, rating should be capped at 30 and action must remain WAIT.

2. Defensive manipulation handling
- Spoofing, layering, wash-trade style volume, and pump/dump conditions are risk filters.
- The bot should not try to attack manipulation; it should avoid trading into it.
- Spoof risk above 60 should force WAIT.
- Spoof risk 35 to 60 should cap rating at 55.

3. Liquidity and spread weighting
- Deep, tight books get rating support.
- Thin books, low visible depth, and wide spreads reduce rating strongly.
- SHIB/DOGE/XLM-style thinner books need stricter friction and lower rating unless PnL remains clearly positive.

4. Risk management
- Position size must be realistic and derived from visible liquidity.
- Rating should reward smaller, fillable trade sizes and penalize insufficient visible liquidity.

5. Backtesting mindset in live mode
- Treat live score as a probability/risk rating, not certainty.
- Rating should include reason fields: edge, liquidity, manipulation, spread, PnL, risk.

Recommended score formula:

base = abs(bookImbalance) + 20
+ pnlEdgeScore
+ liquidityScore
- spoofPenalty
- spreadPenalty
- lowDepthPenalty

Caps:
- no clear Actual PnL: max rating 30
- spoofRisk > 60: max rating 25 and WAIT
- spoofRisk 35-60: max rating 55
- wide spread: max rating 50
- insufficient visible liquidity: max rating 20

JSON fields to expose:
- ratingScore
- ratingGrade
- ratingReasons
- ratingCaps
- actualPnlGate
- adaptiveLiquidityModel
- spoofRisk
