// Downloads the exact GeckoLib builds used in release checks from Modrinth, checking their SHA-1.
const fs = require('fs'), path = require('path'), crypto = require('crypto');
const root = process.argv[2];
const UA = { headers: { 'User-Agent': 'afterself-mod-compat-check' } };
const wanted = [
  ['1.20.1', 'forge', '4.8.4', 'forge-1.20.1-4.8.4'],
  ['1.20.1', 'neoforge', '4.4.9', 'forge-1.20.1-4.4.9'],
  ['1.21', 'forge', '4.5.8', 'forge-1.21-4.5.8'],
  ['1.21', 'neoforge', '4.5.8', 'neoforge-1.21-4.5.8'],
  ['1.21.1', 'forge', '4.9.3', 'forge-1.21.1-4.9.3'],
  ['1.21.1', 'neoforge', '4.9.3', 'neoforge-1.21.1-4.9.3'],
];
(async () => {
  for (const [mc, loader, version, name] of wanted) {
    const q = `game_versions=${encodeURIComponent(JSON.stringify([mc]))}&loaders=${encodeURIComponent(JSON.stringify([loader]))}`;
    const list = await (await fetch(`https://api.modrinth.com/v2/project/geckolib/version?${q}`, UA)).json();
    const v = list.find(x => x.version_number === version);
    if (!v) { console.log('NOT FOUND', mc, loader, version); continue; }
    const f = v.files.find(x => x.primary) || v.files[0];
    const buf = Buffer.from(await (await fetch(f.url, UA)).arrayBuffer());
    const ok = crypto.createHash('sha1').update(buf).digest('hex') === f.hashes.sha1;
    fs.mkdirSync(path.join(root, 'gl'), { recursive: true });
    fs.writeFileSync(path.join(root, 'gl', name + '.jar'), buf);
    console.log(name, f.filename, ok ? 'sha1 OK' : 'SHA1 MISMATCH', 'loaders:', v.loaders.join(','));
  }
})();
