import { useEffect, useState } from 'react';
import { StyleSheet, View, ActivityIndicator, BackHandler } from 'react-native';
import { StatusBar } from 'expo-status-bar';
import { WebView } from 'react-native-webview';
import { Asset } from 'expo-asset';
import { File, Paths } from 'expo-file-system';

// ブロック壊しゲーム本体 (android/app/game-src/game.html と同一ファイル) を
// WebView で読み込むだけの薄いラッパー。ネイティブ機能(広告/課金/通知)は
// game.html 側の `if (window.AndroidXxx)` 判定で自動的にスキップされるため未実装でも動く。
export default function App() {
  const [gameUri, setGameUri] = useState(null);

  useEffect(() => {
    (async () => {
      const asset = Asset.fromModule(require('./assets/game/game.html'));
      await asset.downloadAsync();

      // Metroのキャッシュ場所は毎回変わりうるので、安定したパスにコピーしてから読み込む
      const dest = new File(Paths.document, 'game.html');
      if (dest.exists) dest.delete();
      const src = new File(asset.localUri);
      src.copy(dest);

      setGameUri(dest.uri);
    })();
  }, []);

  useEffect(() => {
    // Androidの物理戻るボタンでアプリが終了しないようにする(ゲーム内で処理させる)
    const sub = BackHandler.addEventListener('hardwareBackPress', () => true);
    return () => sub.remove();
  }, []);

  if (!gameUri) {
    return (
      <View style={styles.loading}>
        <ActivityIndicator size="large" />
        <StatusBar style="auto" />
      </View>
    );
  }

  return (
    <View style={styles.container}>
      <WebView
        source={{ uri: gameUri }}
        originWhitelist={['*']}
        allowingReadAccessToURL={Paths.document.uri}
        javaScriptEnabled
        domStorageEnabled
        allowFileAccessFromFileURLs
        allowUniversalAccessFromFileURLs
        setSupportMultipleWindows={false}
        style={styles.webview}
      />
      <StatusBar style="auto" />
    </View>
  );
}

const styles = StyleSheet.create({
  container: { flex: 1, backgroundColor: '#a0d8f1' },
  webview: { flex: 1 },
  loading: { flex: 1, alignItems: 'center', justifyContent: 'center', backgroundColor: '#a0d8f1' },
});
