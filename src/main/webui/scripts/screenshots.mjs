import assert from 'node:assert/strict';
import { mkdir } from 'node:fs/promises';
import { resolve, join } from 'node:path';
import { fileURLToPath } from 'node:url';
import { parseArgs } from 'node:util';
import { chromium } from 'playwright';

const { values } = parseArgs({ options: {
  url: { type: 'string', default: 'http://127.0.0.1:8080' },
  output: { type: 'string', default: fileURLToPath(new URL('../../../../docs/', import.meta.url)) },
  'chrome-path': { type: 'string' },
} });
const output = resolve(values.output);
await mkdir(output, { recursive: true });
const browser = await chromium.launch({
  headless: true,
  ...(values['chrome-path'] ? { executablePath: values['chrome-path'] } : {}),
});

try {
  const page = await browser.newPage({
    viewport: { width: 1920, height: 1080 },
    deviceScaleFactor: 1,
    locale: 'en-GB',
    timezoneId: 'Europe/Paris',
    reducedMotion: 'reduce',
  });
  const errors = [];
  page.on('pageerror', error => errors.push(error.message));

  async function capture(name, locator) {
    await page.evaluate(async () => {
      await Promise.all(document.getAnimations()
        .filter(animation => animation.effect?.getTiming().iterations !== Infinity)
        .map(animation => animation.finished.catch(() => {})));
      await new Promise(resolve => requestAnimationFrame(() => requestAnimationFrame(resolve)));
    });
    await (locator ?? page).screenshot({ path: join(output, `${name}.png`), animations: 'disabled' });
    console.log(`Captured ${name}.png`);
  }

  const timestamp = Math.floor(Date.now() / 1000);
  const rates = { eur: 92000, usd: 101200, timestamp };
  await page.route('**/api/wallet/prices', route => route.fulfill({ json: rates }));
  await page.route('**/api/wallet/fees', route => route.fulfill({
    json: { fastest: 8, halfHour: 6, hour: 4, economy: 2, minimum: 1, timestamp },
  }));
  await page.route('**/api/wallet/balance-history', async route => {
    const response = await route.fetch();
    assert.ok(response.ok(), 'Demo balance history must be available');
    const history = await response.json();
    assert.ok(history.length > 1, 'Demo history must contain multiple points');
    const lastIndex = history.length - 1;
    const variation = index => index * 110 + Math.sin(index / 8) * 2400 + Math.sin(index / 3) * 650;
    await route.fulfill({ json: history.map((point, index) => {
      const priceEur = Math.round(rates.eur + variation(index) - variation(lastIndex));
      const priceUsd = Math.round(priceEur * rates.usd / rates.eur);
      return { ...point, priceEur, priceUsd, priceTime: point.time, priceStale: false,
        valueEur: point.balanceSats / 100000000 * priceEur,
        valueUsd: point.balanceSats / 100000000 * priceUsd };
    }) });
  });
  await page.routeWebSocket('**/api/**', socket => {
    const server = socket.connectToServer();
    let received = false;
    server.onMessage(message => {
      if (!received) socket.send(message);
      received = true;
    });
  });

  await page.goto(values.url, { waitUntil: 'networkidle' });
  await page.getByRole('button', { name: /^Show details for transaction/ }).first().waitFor();
  await page.evaluate(() => document.fonts.ready);
  assert.equal(await page.getByText(/Fiat estimate unavailable/).count(), 0);
  await page.getByRole('button', { name: 'USD', exact: true }).click();
  assert.equal(await page.getByText(/Fiat estimate unavailable/).count(), 0);
  await page.getByRole('button', { name: 'EUR', exact: true }).click();
  await page.getByRole('heading', { name: 'Wallet Viewer', exact: true }).click();
  assert.equal(await page.evaluate(() => document.documentElement.scrollWidth > innerWidth), false);
  await page.mouse.move(0, 0);
  await capture('dashboard');

  const sentRow = page.getByRole('row').filter({ has: page.getByText('Sent', { exact: true }) }).nth(2);
  await sentRow.getByRole('button', { name: /^Show details for transaction/ }).click();
  const dialog = page.getByRole('dialog');
  await dialog.getByText('Total inputs', { exact: true }).waitFor();
  await page.mouse.move(0, 0);
  await capture('transaction');
  await page.keyboard.press('Escape');
  await dialog.waitFor({ state: 'hidden' });

  const selfRow = page.getByRole('row').filter({ has: page.getByText('Self-transfer', { exact: true }) }).first();
  await selfRow.getByRole('button', { name: /^Show details for transaction/ }).click();
  await dialog.getByText('Total inputs', { exact: true }).waitFor();
  await page.mouse.move(0, 0);
  await capture('consolidation');
  await dialog.getByRole('tab', { name: 'Inputs / Outputs', exact: true }).click();
  await page.mouse.move(0, 0);
  await capture('inputs-outputs');
  await page.keyboard.press('Escape');
  await dialog.waitFor({ state: 'hidden' });

  const historyResponse = page.waitForResponse(response => response.url().includes('/balance-history') && response.ok());
  await page.getByRole('tab', { name: 'Chart', exact: true }).click();
  await historyResponse;
  await page.getByText('Balance over time', { exact: true }).waitFor();
  await page.getByRole('button', { name: 'BTC price (EUR)', exact: true }).click();
  assert.equal(await page.getByText(/Price history update unavailable|Fiat values are unavailable/).count(), 0);
  await page.waitForFunction(() => [...document.querySelectorAll('canvas')].some(canvas => {
    const context = canvas.getContext('2d');
    if (!context || canvas.width < 400 || canvas.height < 100) return false;
    const pixels = context.getImageData(0, 0, canvas.width, canvas.height).data;
    let orangePixels = 0;
    for (let offset = 0; offset < pixels.length; offset += 4) {
      if (pixels[offset] > 150 && pixels[offset + 1] > 60 && pixels[offset + 1] < 200 && pixels[offset + 2] < 100) orangePixels++;
    }
    return orangePixels > 100;
  }));
  await page.mouse.move(0, 0);
  await page.evaluate(() => scrollTo(0, document.documentElement.scrollHeight - innerHeight));
  await capture('balance-history');
  const canvasIndex = await page.locator('canvas').evaluateAll(canvases => canvases.reduce((largest, canvas, index) =>
    canvas.width * canvas.height > canvases[largest].width * canvases[largest].height ? index : largest, 0));
  const chartBounds = await page.locator('canvas').nth(canvasIndex).boundingBox();
  assert.ok(chartBounds);
  const tooltip = page.getByRole('tooltip').filter({ hasText: /transaction/ });
  for (let offset = 70; offset < chartBounds.width - 50; offset += 10) {
    await page.mouse.move(chartBounds.x + offset, chartBounds.y + 120);
    if (await tooltip.isVisible()) break;
  }
  await tooltip.waitFor();
  await capture('chart-transaction');

  await page.mouse.move(0, 0);
  await page.getByRole('tab', { name: /^UTXO/ }).click();
  const utxoTable = page.getByRole('tabpanel').filter({ has: page.getByRole('heading', { name: 'Unspent outputs', exact: true }) }).getByRole('table');
  await utxoTable.waitFor();
  await capture('utxos', utxoTable);
  await page.getByRole('button', { name: 'Enlarge receive address QR code' }).click();
  await page.getByRole('heading', { name: 'Receive address QR code' }).waitFor();
  await page.getByRole('dialog', { name: 'Receive address QR code' }).getByRole('img').evaluate(image => image.decode());
  await page.mouse.move(0, 0);
  await capture('receive');
  await page.keyboard.press('Escape');
  await page.getByRole('tab', { name: /^Activity/ }).click();
  await page.getByRole('button', { name: 'Network fees', exact: true }).click();
  const fees = page.getByRole('region', { name: 'Network fees', exact: true });
  await fees.waitFor();
  await page.mouse.move(0, 0);
  await capture('network-fees', fees.locator(':scope > div'));
  assert.deepEqual(errors, []);
  console.log(`Nine desktop screenshots saved to ${output}`);
} finally {
  await browser.close();
}