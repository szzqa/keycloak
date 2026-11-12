const {defineConfig}=require('@playwright/test');
module.exports=defineConfig({testDir:'./integration-tests',testMatch:'rehearsal.spec.cjs',timeout:60000,workers:1,
  reporter:[['list'],['json',{outputFile:'.local/rehearsal/browser-results.json'}]],
  use:{channel:'chrome',headless:true,trace:'off',screenshot:'off'}});
