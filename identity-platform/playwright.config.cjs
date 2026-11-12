const {defineConfig} = require('@playwright/test');
module.exports = defineConfig({testDir: './integration-tests', testIgnore: 'rehearsal.spec.cjs', timeout: 60000, workers: 1,
  reporter: [['list'], ['json', {outputFile: '.local/test-results.json'}]],
  use: {channel: 'chrome', headless: true, trace: 'off', screenshot: 'off'},
});
