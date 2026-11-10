import type { CapacitorConfig } from '@capacitor/cli';

const config: CapacitorConfig = {
  appId: 'com.jeevavibeapp.spendwise',
  appName: 'SpendWise',
  // The APK must bundle ONLY a loading shell — never a second, competing app.
  // This previously pointed at `dist` (the legacy React prototype), which the
  // WebView loaded as history entry #1; a single Back press then dropped the
  // user into that old app with its own empty storage.
  webDir: 'webshell',
  server: {
    // The embedded Chaquopy/Flask server runs at http://127.0.0.1:8765.
    // Allow the WebView to navigate there (instead of opening an external
    // browser) and permit cleartext for the loopback address.
    allowNavigation: ['127.0.0.1'],
    cleartext: true,
  },
};

export default config;
