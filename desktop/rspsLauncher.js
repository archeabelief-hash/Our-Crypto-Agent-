const { spawn } = require('child_process');
const path = require('path');
const { ensureFiles, baseDir } = require('./rspsBootstrap');
const config = require('./rspsConfig');

async function launchRSPS() {
  await ensureFiles();

  const serverPath = path.join(baseDir, config.downloads.server.folderName);
  const clientPath = path.join(baseDir, config.downloads.client.folderName);

  console.log('Starting Login Server...');
  spawn('gradlew.bat', [config.launch.loginTask], { cwd: serverPath, shell: true });

  setTimeout(() => {
    console.log('Starting Game Server...');
    spawn('gradlew.bat', [config.launch.gameTask], { cwd: serverPath, shell: true });
  }, 5000);

  setTimeout(() => {
    console.log('Launching Client...');
    spawn('gradlew.bat', [config.launch.clientTask], { cwd: clientPath, shell: true });
  }, 10000);
}

module.exports = { launchRSPS };