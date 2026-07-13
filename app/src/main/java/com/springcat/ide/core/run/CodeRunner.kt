package com.springcat.ide.core.run

import com.springcat.ide.core.lang.Language

/**
 * Turns editable source into a self-contained HTML document that a WebView can
 * actually run — so HTML pages and React/JSX components are testable on-device.
 *
 * The React and Babel runtimes are bundled under `assets/runtime/`, so JSX is
 * transpiled locally with no network access.
 */
object CodeRunner {

    /** Languages that can be executed/previewed in the WebView runner. */
    fun isRunnable(language: Language): Boolean = when (language) {
        Language.HTML, Language.JAVASCRIPT, Language.TYPESCRIPT -> true
        else -> false
    }

    /** True when the source should be treated as a React/JSX/JS program. */
    private fun isScript(language: Language): Boolean =
        language == Language.JAVASCRIPT || language == Language.TYPESCRIPT

    fun buildDocument(source: String, language: Language): String = when {
        language == Language.HTML -> source
        isScript(language) -> scriptDocument(source)
        else -> "<!doctype html><meta charset=\"utf-8\"><body>Not runnable.</body>"
    }

    private fun scriptDocument(userCode: String): String = """
<!doctype html>
<html>
<head>
<meta charset="utf-8">
<meta name="viewport" content="width=device-width, initial-scale=1, maximum-scale=1">
<style>
  html,body { margin:0; padding:16px; font-family:-apple-system,Roboto,sans-serif;
              color:#e6edea; background:#0b1512; }
  #root:empty::before { content:"Define an App component (or render to #root) to see output.";
                        color:#6b7c74; font-size:13px; }
  .springcat-error { color:#ff6b6b; white-space:pre-wrap; font-family:monospace;
                     border-left:3px solid #ff6b6b; padding-left:10px; margin-top:12px; }
</style>
<script src="runtime/react.production.min.js"></script>
<script src="runtime/react-dom.production.min.js"></script>
<script src="runtime/babel.min.js"></script>
</head>
<body>
<div id="root"></div>
<script type="text/babel" data-presets="react,typescript" data-type="module">
const { useState, useEffect, useRef, useMemo, useCallback, useReducer, Fragment } = React;
try {
${userCode}

  // Convenience: auto-mount a component named `App` if the user defined one.
  if (typeof App !== 'undefined') {
    ReactDOM.createRoot(document.getElementById('root')).render(React.createElement(App));
  }
} catch (err) {
  const pre = document.createElement('pre');
  pre.className = 'springcat-error';
  pre.textContent = 'Runtime error: ' + (err && err.stack ? err.stack : err);
  document.body.appendChild(pre);
  console.error(err);
}
</script>
</body>
</html>
""".trimIndent()
}
