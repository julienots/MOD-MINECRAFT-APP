// Rend le banc d'essai du moteur 3D (généré par RenderTest dans core/build/gl-check) avec Chromium sans écran.
// Échoue si un shader ne compile pas ou si WebGL signale une erreur. Usage : node run.js <index.html> <capture.png>
const { chromium } = require('playwright');

(async () => {
  const [page_, shot] = process.argv.slice(2);
  const browser = await chromium.launch({ args: ['--use-angle=swiftshader', '--enable-unsafe-swiftshader', '--ignore-gpu-blocklist'] });
  const page = await browser.newPage({ viewport: { width: 1340, height: 1500 } });
  page.on('pageerror', e => console.log('Erreur de page :', e.message));
  await page.goto('file://' + require('path').resolve(page_));
  await page.waitForFunction(() => window.RESULT && window.RESULT.done, null, { timeout: 120000 });
  const result = await page.evaluate(() => window.RESULT);
  await page.screenshot({ path: shot, fullPage: true });
  await browser.close();
  if (result.errors.length) {
    console.error('Moteur 3D : ' + result.errors.length + ' erreur(s)\n' + result.errors.join('\n'));
    process.exit(1);
  }
  console.log('Moteur 3D : shaders compilés et 6 scènes rendues sans erreur (' + shot + ').');
})().catch(e => { console.error(e); process.exit(1); });
