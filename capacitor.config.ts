import type { CapacitorConfig } from '@capacitor/cli';

const config: CapacitorConfig = {
  appId: 'com.jeevavibeapp.spendwise',
  appName: 'SpendWise',
  webDir: 'dist',
  server: {
    // The embedded Chaquopy/Flask server runs at http://127.0.0.1:8765.
    // Allow the WebView to navigate there (instead of opening an external
    // browser) and permit cleartext for the loopback address.
    allowNavigation: ['127.0.0.1'],
    cleartext: true,
  },
};

export default config;
