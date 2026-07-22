package com.springcat.app;

import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.os.Bundle;
import android.view.View;
import android.widget.Button;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;

public class MainActivity extends AppCompatActivity {

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        TextView startCommand = findViewById(R.id.startCommand);
        TextView serverUrl = findViewById(R.id.serverUrl);
        Button copyCommand = findViewById(R.id.copyCommand);
        Button copyUrl = findViewById(R.id.copyUrl);

        copyCommand.setOnClickListener(v ->
                copyToClipboard("springcat start command", startCommand.getText().toString()));
        copyUrl.setOnClickListener(v ->
                copyToClipboard("springcat server url", serverUrl.getText().toString()));
    }

    private void copyToClipboard(String label, String text) {
        ClipboardManager clipboard =
                (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
        clipboard.setPrimaryClip(ClipData.newPlainText(label, text));
        Toast.makeText(this, getString(R.string.copied), Toast.LENGTH_SHORT).show();
    }
}
