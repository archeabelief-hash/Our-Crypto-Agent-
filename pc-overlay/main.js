const { app, BrowserWindow, ipcMain } = require('electron');
const path = require('path');

let win;
function createWindow() {
  win = new BrowserWindow({
    width: 760,
    height: 900,
    minWidth: 560,
    minHeight: 640,
    alwaysOnTop: true,
    frame: true,
    autoHideMenuBar: true,
    webPreferences: {
      contextIsolation: true,
      nodeIntegration: false,
      sandbox: true
    }
  });
  win.loadFile('index.html');
  win.setAlwaysOnTop(true, 'floating');
}

app.whenReady().then(createWindow);
app.on('window-all-closed', () => app.quit());

ipcMain.on('set-always-on-top', (_event, value) => {
  if (win) win.setAlwaysOnTop(Boolean(value), 'floating');
});
