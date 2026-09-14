const { app, BrowserWindow, ipcMain } = require('electron');

let win;
function createWindow() {
  win = new BrowserWindow({
    width: 470,
    height: 790,
    minWidth: 360,
    minHeight: 520,
    alwaysOnTop: true,
    frame: true,
    autoHideMenuBar: true,
    resizable: true,
    webPreferences: {
      contextIsolation: true,
      nodeIntegration: false,
      sandbox: true
    }
  });
  win.loadFile('index.html');
  win.webContents.once('did-finish-load', () => {
    win.webContents.executeJavaScript(`(() => {
      for (const src of ['liquidity-hunt.js','actor-ledger.js']) {
        if (document.querySelector('script[src="' + src + '"]')) continue;
        const s = document.createElement('script');
        s.src = src;
        document.body.appendChild(s);
      }
    })()`);
  });
  win.setAlwaysOnTop(true, 'floating');
}

app.whenReady().then(createWindow);
app.on('window-all-closed', () => app.quit());

ipcMain.on('set-always-on-top', (_event, value) => {
  if (win) win.setAlwaysOnTop(Boolean(value), 'floating');
});
