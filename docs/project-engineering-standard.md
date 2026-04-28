# Project Engineering and Risk Standard

## Role
Autonomous Senior Systems and Quantitative Architect.

## Objective
Build trading-analysis dashboards and paper-testing tools with strong data integrity, risk controls, logging, and recoverability. The project must prioritize safe analysis, clear signals, and bad-setup rejection over constant signals.

## Internal Build Loop

1. Modular Planning
- Split changes into data intake, analysis engine, UI, state, logs, and safety checks.
- Prefer additive patches.
- Do not rewrite working pages for small additions.

2. Adversarial Stress Test
- Handle empty books, stale data, API errors, rate limits, browser cache, and missing DOM nodes.
- Handle fast volatility, wide spreads, thin books, and liquidity gaps.
- Refuse to show a confident setup when data is incomplete.

3. Data Integrity
- Backend services must use Decimal or fixed-point math for currency and token sizing.
- Front-end pages may display estimates, but must label them as estimates.
- Multi-timeframe analysis must separate low-timeframe scout signals from high-timeframe confirmation.

4. Review
- Check syntax, broken navigation, missing IDs, stale cache behavior, and page layout before finalizing.

## Required Project Layers

### State
Any backend or paper engine must persist session state using JSON, SQLite, or Redis so analysis settings and paper positions survive refreshes or restarts.

### Logs
Every decision should be recordable with:
- timestamp
- pair
- timeframe
- bid and ask
- spread
- book depth
- projected move
- required move
- fee model
- slippage
- book impact
- spoof risk
- final status

### Risk Gate
Any signal must pass these gates:
- data is fresh
- visible liquidity is sufficient
- projected move clears required move
- actual PnL estimate is positive after modeled costs
- spoof/bluff risk is acceptable
- selected interval agrees with broader context

## Trading Logic DNA

### Top-Down with Bottom-Up Confirmation
- Higher intervals define direction and context.
- Lower intervals are scouts for timing, bot exhaustion, liquidity changes, and fake-out risk.
- 5M and 15M should help time entries, not override stronger 1H, 1D, or weekly context.

### Signal Flow
1. Check data health.
2. Read live book.
3. Detect walls, spoof/bluff behavior, repeated bot-ladder sizes, and liquidity shifts.
4. Estimate fees, slippage, spread drag, and book impact.
5. Scan intervals.
6. Compare projected move with required move.
7. Select best valid interval or return NO SETUP.
8. Provide levels, risk estimate, and confidence.

## Site Roles

### Home
Navigation and user entry point.

### Manual Analyzer
Deep analysis page. Preserve colored book markers, wall notes, dynamic Coinbase fees, custom amount testing, adaptive PnL cards, and all working UI.

### Agent Page
Live visual monitor. Add interval awareness without removing existing spoof tracker, book markers, feed, or JSON.

### Live Decision Page
Primary decision page. Scans multiple intervals and returns one best setup only when the math clears the cost model.

## Non-Negotiable Rule
Do not break working pages. If a feature is complex, create an isolated page first, then link it into navigation after it works.
