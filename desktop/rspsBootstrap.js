const fs = require('fs');
const path = require('path');
const https = require('https');
const AdmZip = require('adm-zip');
const config = require('./rspsConfig');

const baseDir = path.join(require('os').homedir(), config.appFolderName);

function downloadFile(id, dest) {
  return new Promise((resolve, reject) => {
    const url = `https://drive.google.com/uc?export=download&id=${id}`;
    const file = fs.createWriteStream(dest);
    https.get(url, res => {
      res.pipe(file);
      file.on('finish', () => file.close(resolve));
    }).on('error', reject);
  });
}

async function ensureFiles() {
  if (!fs.existsSync(baseDir)) fs.mkdirSync(baseDir, { recursive: true });

  for (const key of Object.keys(config.downloads)) {
    const item = config.downloads[key];
    const folderPath = path.join(baseDir, item.folderName);

    if (!fs.existsSync(folderPath)) {
      const zipPath = path.join(baseDir, item.fileName);

      console.log('Downloading', item.fileName);
      await downloadFile(item.id, zipPath);

      console.log('Extracting', item.fileName);
      const zip = new AdmZip(zipPath);
      zip.extractAllTo(baseDir, true);
    }
  }
}

module.exports = { ensureFiles, baseDir };