(function(){
  if (document.getElementById('magician-overlay')) return;

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