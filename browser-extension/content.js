(function(){
  if (document.getElementById('magician-overlay')) return;

  const style = document.createElement('style');
  style.innerHTML = `
    #magician-overlay { position: fixed; top: 80px; right: 20px; width: 280px; background: #0b111c; border: 1px solid #24324f; border-radius: 10px; color: #eef5ff; font-family: system-ui; z-index: 999999; box-shadow: 0 10px 40px rgba(0,0,0,0.6); }
    #magician-header { padding: 10px; font-weight: 700; border-bottom: 1px solid #24324f; }
    .magician-row { padding: 8px 10px; border-bottom: 1px solid rgba(255,255,255,0.05); }
    .magician-btn { width: 100%; padding: 8px; background: #111c30; border: 1px solid #4f8cff; color: #eef5ff; border-radius: 6px; cursor: pointer; }
  `;
  document.head.appendChild(style);

  const box = document.createElement('div');
  box.id = 'magician-overlay';

  box.innerHTML = `
    <div id="magician-header">Magician Overlay</div>
    <div class="magician-row">Signal: <span id="sig">WAIT</span></div>
    <div class="magician-row">Entry: <span id="entry">-</span></div>
    <div class="magician-row">Target: <span id="target">-</span></div>
    <div class="magician-row">Stop: <span id="stop">-</span></div>
    <div class="magician-row">
      <button id="copyTrade" class="magician-btn">Copy Trade</button>
    </div>
  `;

  document.body.appendChild(box);

  const demo = {
    signal: 'READY_LONG',
    entry: 'Market',
    target: '+0.6%',
    stop: '-0.3%'
  };

  document.getElementById('sig').innerText = demo.signal;
  document.getElementById('entry').innerText = demo.entry;
  document.getElementById('target').innerText = demo.target;
  document.getElementById('stop').innerText = demo.stop;

  document.getElementById('copyTrade').onclick = () => {
    const text = `Trade:\n${demo.signal}\nEntry: ${demo.entry}\nTarget: ${demo.target}\nStop: ${demo.stop}`;
    navigator.clipboard.writeText(text);
  };
})();