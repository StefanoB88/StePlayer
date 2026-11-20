const path = require('path');
const { getDefaultConfig, mergeConfig } = require('@react-native/metro-config');

// The list/guide/search/sync logic is shared with the Samsung/LG TV app: ../tizenApp/src/core (TypeScript).
const core = path.resolve(__dirname, '../tizenApp/src/core');

/** @type {import('metro-config').MetroConfig} */
const defaultConfig = getDefaultConfig(__dirname);
const config = {
  watchFolders: [core],
  resolver: {
    // Packages imported by the shared core (pako...) come from this app's node_modules.
    nodeModulesPaths: [path.resolve(__dirname, 'node_modules')],
    // hls.js for Vega is an ES module bundle (src/vendor/hls/hlsjsplayer/dist/hls.mjs).
    sourceExts: [...defaultConfig.resolver.sourceExts, 'mjs'],
  },
};

module.exports = mergeConfig(defaultConfig, config);
