const { getDefaultConfig } = require('expo/metro-config');

const config = getDefaultConfig(__dirname);

// game.html は静的アセットとしてバンドルする(Metroのデフォルトではhtmlはソース扱いのため追加)
config.resolver.assetExts.push('html');

module.exports = config;
