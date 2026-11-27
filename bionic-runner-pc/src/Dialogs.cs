using System;
using System.Collections.Generic;
using System.Drawing;
using System.Windows.Forms;

namespace BionicRunnerPC
{
    /// <summary>
    /// 実行ファイルの説明と「登録名」「引数」の入力欄。
    /// 新規 (registerMode=true): OK = 登録して実行 / Yes = 登録のみ / Retry = ウィンドウで開く / Cancel
    /// 登録済み (registerMode=false): OK = 実行 / Retry = ウィンドウで開く / Abort = 削除 / Cancel
    /// </summary>
    public sealed class ExecDialog : Form
    {
        readonly TextBox nameBox;
        readonly TextBox argsBox;

        public string RegName { get { return nameBox != null ? nameBox.Text : ""; } }
        public string Args { get { return argsBox.Text; } }

        public ExecDialog(string title, string description, string defaultName, bool registerMode)
        {
            Text = title;
            FormBorderStyle = FormBorderStyle.FixedDialog;
            MaximizeBox = false;
            MinimizeBox = false;
            ShowInTaskbar = false;
            StartPosition = FormStartPosition.CenterParent;
            AutoScaleMode = AutoScaleMode.Font;
            Font = SystemFonts.MessageBoxFont;
            AutoSize = true;
            AutoSizeMode = AutoSizeMode.GrowAndShrink;
            Padding = new Padding(16);

            var table = new TableLayoutPanel
            {
                AutoSize = true,
                AutoSizeMode = AutoSizeMode.GrowAndShrink,
                ColumnCount = 2,
                Dock = DockStyle.Fill,
            };
            table.ColumnStyles.Add(new ColumnStyle(SizeType.AutoSize));
            table.ColumnStyles.Add(new ColumnStyle(SizeType.Absolute, 380));

            var msg = new Label
            {
                Text = description,
                AutoSize = true,
                MaximumSize = new Size(480, 0),
                Margin = new Padding(0, 0, 0, 12),
            };
            table.Controls.Add(msg, 0, 0);
            table.SetColumnSpan(msg, 2);

            int row = 1;
            if (registerMode)
            {
                nameBox = new TextBox { Text = defaultName, Width = 370 };
                table.Controls.Add(new Label { Text = "登録名", AutoSize = true, Anchor = AnchorStyles.Left }, 0, row);
                table.Controls.Add(nameBox, 1, row++);
            }
            argsBox = new TextBox { Width = 370 };
            table.Controls.Add(new Label { Text = "引数 (省略可)", AutoSize = true, Anchor = AnchorStyles.Left }, 0, row);
            table.Controls.Add(argsBox, 1, row++);

            var buttons = new FlowLayoutPanel
            {
                FlowDirection = FlowDirection.RightToLeft,
                AutoSize = true,
                Dock = DockStyle.Fill,
                Margin = new Padding(0, 16, 0, 0),
            };
            var cancel = new Button { Text = "キャンセル", DialogResult = DialogResult.Cancel, AutoSize = true };
            Button primary, secondary;
            if (registerMode)
            {
                primary = new Button { Text = "登録して実行", DialogResult = DialogResult.OK, AutoSize = true };
                secondary = new Button { Text = "登録のみ", DialogResult = DialogResult.Yes, AutoSize = true };
            }
            else
            {
                primary = new Button { Text = "実行", DialogResult = DialogResult.OK, AutoSize = true };
                secondary = new Button { Text = "削除", DialogResult = DialogResult.Abort, AutoSize = true };
            }
            // 「ウィンドウで開く」: ゲームなど画面のあるアプリや、vi のような全画面のプログラム向け
            var window = new Button { Text = "ウィンドウで開く", DialogResult = DialogResult.Retry, AutoSize = true };
            buttons.Controls.Add(cancel);
            buttons.Controls.Add(primary);
            buttons.Controls.Add(window);
            buttons.Controls.Add(secondary);
            table.Controls.Add(buttons, 0, row);
            table.SetColumnSpan(buttons, 2);

            Controls.Add(table);
            AcceptButton = primary;
            CancelButton = cancel;
            Shown += (s, e) => (registerMode ? nameBox : argsBox).Focus();
        }
    }

    /// <summary>一覧から 1 つ選ぶ。選ばなければ -1</summary>
    public sealed class ListDialog : Form
    {
        readonly ListBox list;

        ListDialog(string title, string message, IList<string> items)
        {
            Text = title;
            StartPosition = FormStartPosition.CenterParent;
            ShowInTaskbar = false;
            MinimizeBox = false;
            Font = SystemFonts.MessageBoxFont;
            Size = new Size(560, 420);
            Padding = new Padding(12);

            var msg = new Label { Text = message, Dock = DockStyle.Top, AutoSize = false, Height = 44 };
            list = new ListBox { Dock = DockStyle.Fill, IntegralHeight = false };
            foreach (var i in items) list.Items.Add(i);
            if (items.Count > 0) list.SelectedIndex = 0;
            list.DoubleClick += (s, e) => { if (list.SelectedIndex >= 0) { DialogResult = DialogResult.OK; } };

            var buttons = new FlowLayoutPanel { FlowDirection = FlowDirection.RightToLeft, Dock = DockStyle.Bottom, Height = 40 };
            var cancel = new Button { Text = "閉じる", DialogResult = DialogResult.Cancel, AutoSize = true };
            var ok = new Button { Text = "選択", DialogResult = DialogResult.OK, AutoSize = true };
            buttons.Controls.Add(cancel);
            buttons.Controls.Add(ok);

            Controls.Add(list);
            Controls.Add(msg);
            Controls.Add(buttons);
            AcceptButton = ok;
            CancelButton = cancel;
        }

        public static int Show(IWin32Window owner, string title, string message, IList<string> items)
        {
            using (var d = new ListDialog(title, message, items))
            {
                return d.ShowDialog(owner) == DialogResult.OK ? d.list.SelectedIndex : -1;
            }
        }
    }
}
