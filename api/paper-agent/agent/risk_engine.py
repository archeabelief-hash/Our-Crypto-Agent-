def validate_signal(signal, settings, state):
    best = signal.get('best')
    if not best:
        return False

    if best.get('side') != 'LONG':
        return False

    if not best.get('clear'):
        return False

    if signal.get('amount', 0) > settings.max_trade_usd:
        return False

    if state.total_loss() <= -settings.daily_loss_limit:
        return False

    if best.get('pnl', {}).get('actual', 0) <= 0:
        return False

    return True
