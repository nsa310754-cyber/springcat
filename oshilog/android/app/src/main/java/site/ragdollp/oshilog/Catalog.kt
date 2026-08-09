package site.ragdollp.oshilog

/**
 * アプリ内蔵の検索用カタログ（オフライン）。
 * ※ 外部APIではなく同梱データ。項目を足すだけで拡張可能。
 *    将来オンライン検索に差し替える場合は search() を API 呼び出しに変更する。
 */

data class CatalogArtist(val name: String, val kana: String, val group: String = "")
data class CatalogFest(val name: String, val kana: String, val venue: String)

sealed class SearchHit {
    data class Artist(val a: CatalogArtist) : SearchHit()
    data class Fest(val f: CatalogFest) : SearchHit()
}

object Catalog {

    // よみは ひらがな/カタカナ/ローマ字/略称 をスペース区切りで入れて検索ヒットを増やす
    val artists: List<CatalogArtist> = listOf(
        // --- J-POP / バンド / ロック ---
        CatalogArtist("YOASOBI", "よあそび ヨアソビ yoasobi"),
        CatalogArtist("Mrs. GREEN APPLE", "みせすぐりーんあっぷる ミセス ミセスグリーンアップル mrs green apple"),
        CatalogArtist("Official髭男dism", "おふぃしゃるひげだんでぃずむ ひげだん ヒゲダン higedan official hige dandism"),
        CatalogArtist("King Gnu", "きんぐぬー キングヌー king gnu"),
        CatalogArtist("米津玄師", "よねづけんし ヨネヅケンシ kenshi yonezu"),
        CatalogArtist("あいみょん", "あいみょん アイミョン aimyon"),
        CatalogArtist("Vaundy", "ばうんでぃ バウンディ vaundy"),
        CatalogArtist("back number", "ばっくなんばー バックナンバー backnumber back number"),
        CatalogArtist("Ado", "あど アド ado"),
        CatalogArtist("藤井風", "ふじいかぜ フジイカゼ fujii kaze"),
        CatalogArtist("宇多田ヒカル", "うただひかる ウタダヒカル hikaru utada"),
        CatalogArtist("星野源", "ほしのげん ホシノゲン gen hoshino"),
        CatalogArtist("Superfly", "すーぱーふらい スーパーフライ superfly"),
        CatalogArtist("LiSA", "りさ リサ lisa"),
        CatalogArtist("Aimer", "えめ エメ aimer"),
        CatalogArtist("緑黄色社会", "りょくしゃか リョクシャカ りょくおうしょくしゃかい ryokushaka"),
        CatalogArtist("SEKAI NO OWARI", "せかいのおわり セカオワ sekai no owari sekaowa"),
        CatalogArtist("RADWIMPS", "らっどうぃんぷす ラッドウィンプス radwimps"),
        CatalogArtist("ONE OK ROCK", "わんおくろっく ワンオク oneokrock one ok rock"),
        CatalogArtist("BUMP OF CHICKEN", "ばんぷおぶちきん バンプ bump of chicken"),
        CatalogArtist("スピッツ", "すぴっつ スピッツ spitz"),
        CatalogArtist("Mr.Children", "みすたーちるどれん ミスチル mr children mrchildren"),
        CatalogArtist("サザンオールスターズ", "さざんおーるすたーず サザン southern all stars"),
        CatalogArtist("サカナクション", "さかなくしょん サカナクション sakanaction"),
        CatalogArtist("[Alexandros]", "あれきさんどろす アレキサンドロス alexandros"),
        CatalogArtist("UVERworld", "うーばーわーるど ウーバーワールド uverworld"),
        CatalogArtist("東京事変", "とうきょうじへん トウキョウジヘン tokyo jihen"),
        CatalogArtist("椎名林檎", "しいなりんご シイナリンゴ ringo sheena"),
        CatalogArtist("Perfume", "ぱふゅーむ パフューム perfume"),
        CatalogArtist("BABYMETAL", "べびーめたる ベビーメタル babymetal"),
        CatalogArtist("ヨルシカ", "よるしか ヨルシカ yorushika"),
        CatalogArtist("ずっと真夜中でいいのに。", "ずっとまよなかでいいのに ずとまよ zutomayo"),
        CatalogArtist("Eve", "いヴ イブ eve"),
        CatalogArtist("秦基博", "はたもとひろ ハタモトヒロ motohiro hata"),
        CatalogArtist("aiko", "あいこ アイコ aiko"),
        CatalogArtist("いきものがかり", "いきものがかり イキモノガカリ ikimonogakari"),
        CatalogArtist("ゆず", "ゆず ユズ yuzu"),
        CatalogArtist("コブクロ", "こぶくろ コブクロ kobukuro"),
        CatalogArtist("WANIMA", "わにま ワニマ wanima"),
        CatalogArtist("10-FEET", "てんふぃーと テンフィート 10feet ten feet"),
        CatalogArtist("マキシマム ザ ホルモン", "まきしまむざほるもん マキシマムザホルモン maximum the hormone"),
        CatalogArtist("サンボマスター", "さんぼますたー サンボマスター sambomaster"),
        CatalogArtist("クリープハイプ", "くりーぷはいぷ クリープハイプ creephyp"),
        CatalogArtist("SUPER BEAVER", "すーぱーびーばー スーパービーバー super beaver"),
        CatalogArtist("Saucy Dog", "さうしーどっぐ サウシードッグ saucy dog"),
        CatalogArtist("マカロニえんぴつ", "まかろにえんぴつ マカロニエンピツ macaroni empitsu"),
        CatalogArtist("優里", "ゆうり ユウリ yuuri"),
        CatalogArtist("Novelbright", "のべるぶらいと ノベルブライト novelbright"),
        CatalogArtist("Omoinotake", "おもいのたけ オモイノタケ omoinotake"),
        CatalogArtist("imase", "いませ イマセ imase"),
        CatalogArtist("tuki.", "つき ツキ tuki"),
        CatalogArtist("幾田りら", "いくたりら イクタリラ ikura ikuta rira"),
        CatalogArtist("DECO*27", "でこにーなな デコ deco27"),

        // --- 男性アイドル / ダンス&ボーカル ---
        CatalogArtist("Snow Man", "すのーまん スノーマン snow man snowman"),
        CatalogArtist("SixTONES", "すとーんず ストーンズ sixtones"),
        CatalogArtist("なにわ男子", "なにわだんし ナニワダンシ naniwa danshi"),
        CatalogArtist("King & Prince", "きんぐあんどぷりんす キンプリ king and prince kinpri"),
        CatalogArtist("Number_i", "なんばーあい ナンバーアイ number i numberi"),
        CatalogArtist("timelesz", "たいむれす タイムレス timelesz"),
        CatalogArtist("Travis Japan", "とらびすじゃぱん トラビスジャパン travis japan"),
        CatalogArtist("Hey! Say! JUMP", "へいせいじゃんぷ ヘイセイジャンプ hey say jump"),
        CatalogArtist("Kis-My-Ft2", "きすまいふっとつー キスマイ kis my ft2 kisumai"),
        CatalogArtist("WEST.", "うぇすと ウエスト ジャニーズウエスト west johnnys west"),
        CatalogArtist("Aぇ! group", "えーぐるーぷ エーグループ ae group aegroup"),
        CatalogArtist("BE:FIRST", "びーふぁーすと ビーファースト befirst be first"),
        CatalogArtist("JO1", "じぇいおーわん ジェイオーワン jo1"),
        CatalogArtist("INI", "あいえぬあい アイエヌアイ ini"),
        CatalogArtist("Da-iCE", "だいす ダイス da-ice daice"),
        CatalogArtist("GENERATIONS", "じぇねれーしょんず ジェネレーションズ generations"),
        CatalogArtist("三代目 J SOUL BROTHERS", "さんだいめじぇいそうるぶらざーず 三代目 sandaime j soul brothers"),
        CatalogArtist("EXILE", "えぐざいる エグザイル exile"),

        // --- 女性アイドル / 坂道・48系 ---
        CatalogArtist("乃木坂46", "のぎざか ノギザカ nogizaka46 nogizaka"),
        CatalogArtist("櫻坂46", "さくらざか サクラザカ sakurazaka46 sakurazaka"),
        CatalogArtist("日向坂46", "ひなたざか ヒナタザカ hinatazaka46 hinatazaka"),
        CatalogArtist("欅坂46", "けやきざか ケヤキザカ keyakizaka46"),
        CatalogArtist("AKB48", "えーけーびー エーケービー akb48 akb"),
        CatalogArtist("NMB48", "えぬえむびー エヌエムビー nmb48 nmb"),
        CatalogArtist("=LOVE", "いこーるらぶ イコールラブ equal love =love"),
        CatalogArtist("≠ME", "のっといこーるみー ノットイコールミー not equal me"),
        CatalogArtist("ももいろクローバーZ", "ももいろくろーばーぜっと ももクロ momoclo momoiro clover z"),
        CatalogArtist("FRUITS ZIPPER", "ふるーつじっぱー フルーツジッパー fruits zipper"),
        CatalogArtist("ME:I", "みーあい ミーアイ me i mei"),

        // --- K-POP ---
        CatalogArtist("BTS", "びーてぃーえす ビーティーエス 防弾少年団 bts bangtan"),
        CatalogArtist("SEVENTEEN", "せぶんてぃーん セブチ seventeen svt"),
        CatalogArtist("TWICE", "とぅわいす トゥワイス twice"),
        CatalogArtist("NewJeans", "にゅーじーんず ニュージーンズ newjeans new jeans"),
        CatalogArtist("IVE", "あいぶ アイブ ive"),
        CatalogArtist("LE SSERAFIM", "るせらふぃむ ルセラフィム le sserafim lesserafim"),
        CatalogArtist("Stray Kids", "すとれいきっず ストレイキッズ stray kids skz"),
        CatalogArtist("ENHYPEN", "えんはいぷん エンハイプン enhypen"),
        CatalogArtist("TOMORROW X TOGETHER", "とぅげざー トゥバ txt tomorrow x together tubatu"),
        CatalogArtist("aespa", "えすぱ エスパ aespa"),
        CatalogArtist("ITZY", "いっじー イッジ itzy"),
        CatalogArtist("BLACKPINK", "ぶらっくぴんく ブラックピンク blackpink"),
        CatalogArtist("(G)I-DLE", "あいどる アイドル gidle g i-dle"),
        CatalogArtist("ZEROBASEONE", "ぜろべーすわん ゼロベースワン zerobaseone zb1"),
        CatalogArtist("RIIZE", "らいず ライズ riize"),
        CatalogArtist("ILLIT", "あいりっと アイリット illit"),
        CatalogArtist("&TEAM", "えいてぃーむ エイティーム and team ampteam"),
        CatalogArtist("NiziU", "にじゅー ニジュー niziu"),

        // --- 声優 / アニメ / 2.5次元 ---
        CatalogArtist("水樹奈々", "みずきなな ミズキナナ nana mizuki"),
        CatalogArtist("花澤香菜", "はなざわかな ハナザワカナ kana hanazawa"),
        CatalogArtist("宮野真守", "みやのまもる ミヤノマモル mamoru miyano"),
        CatalogArtist("鬼頭明里", "きとうあかり キトウアカリ akari kito"),
        CatalogArtist("ReoNa", "れおな レオナ reona"),
        CatalogArtist("ClariS", "くらりす クラリス claris"),
        CatalogArtist("ラブライブ！", "らぶらいぶ ラブライブ love live lovelive"),
        CatalogArtist("Aqours", "あくあ アクア aqours"),
        CatalogArtist("アイドルマスター", "あいどるますたー アイマス imas idolmaster"),
        CatalogArtist("ウマ娘", "うまむすめ ウマムスメ uma musume"),
        CatalogArtist("あんさんぶるスターズ", "あんさんぶるすたーず あんスタ ensemble stars enst"),
        CatalogArtist("ヒプノシスマイク", "ひぷのしすまいく ヒプマイ hypnosis mic hypmic"),
        CatalogArtist("すとぷり", "すとろべりーぷりんす すとぷり strawberry prince"),
        CatalogArtist("初音ミク", "はつねみく ハツネミク hatsune miku vocaloid")
    ).distinctBy { it.name }

    val fests: List<CatalogFest> = listOf(
        CatalogFest("FUJI ROCK FESTIVAL", "ふじろっく フジロック fuji rock", "苗場スキー場（新潟）"),
        CatalogFest("SUMMER SONIC", "さまーそにっく サマソニ summer sonic", "幕張メッセ / 万博記念公園"),
        CatalogFest("ROCK IN JAPAN FESTIVAL", "ろっきん ロッキン rock in japan rijf", "蘇我スポーツ公園（千葉）"),
        CatalogFest("COUNTDOWN JAPAN", "かうんとだうんじゃぱん カウントダウンジャパン cdj countdown japan", "幕張メッセ"),
        CatalogFest("VIVA LA ROCK", "びばらろっく ビバラ viva la rock", "さいたまスーパーアリーナ"),
        CatalogFest("JAPAN JAM", "じゃぱんじゃむ ジャパンジャム japan jam", "蘇我スポーツ公園（千葉）"),
        CatalogFest("RISING SUN ROCK FESTIVAL", "らいじんぐさん ライジングサン rising sun rsr", "石狩湾新港（北海道）"),
        CatalogFest("METROCK", "めとろっく メトロック metrock", "東京 / 大阪"),
        CatalogFest("SWEET LOVE SHOWER", "すうぃーとらぶしゃわー ラブシャ sweet love shower", "山中湖交流プラザ（山梨）"),
        CatalogFest("COMING KOBE", "かみんぐこうべ カミングコウベ coming kobe", "神戸"),
        CatalogFest("OTODAMA", "おとだま オトダマ otodama", "泉大津フェニックス（大阪）"),
        CatalogFest("MONSTER baSH", "もんすたーばっしゅ モンスターバッシュ monster bash", "国営讃岐まんのう公園（香川）"),
        CatalogFest("WILD BUNCH FEST", "わいるどばんち ワイルドバンチ wild bunch", "山口きらら博記念公園"),
        CatalogFest("京都大作戦", "きょうとだいさくせん キョウトダイサクセン kyoto daisakusen", "京都・宇治市 太陽が丘"),
        CatalogFest("ARABAKI ROCK FEST.", "あらばき アラバキ arabaki", "みちのく杜の湖畔公園（宮城）"),
        CatalogFest("SATANIC CARNIVAL", "さたにっくかーにばる サタニック satanic carnival", "幕張メッセ"),
        CatalogFest("PUNKSPRING", "ぱんくすぷりんぐ パンクスプリング punkspring", "幕張メッセ 他"),
        CatalogFest("KNOTFEST JAPAN", "のっとふぇす ノットフェス knotfest", "幕張メッセ"),
        CatalogFest("GREENROOM FESTIVAL", "ぐりーんるーむ グリーンルーム greenroom", "横浜赤レンガ"),
        CatalogFest("ULTRA JAPAN", "うるとらじゃぱん ウルトラジャパン ultra japan", "東京・お台場"),
        CatalogFest("SONICMANIA", "そにっくまにあ ソニックマニア sonicmania", "幕張メッセ"),
        CatalogFest("TOKYO IDOL FESTIVAL", "とうきょうあいどるふぇすてぃばる ティフ tif tokyo idol festival", "お台場（東京）"),
        CatalogFest("@JAM", "あっとじゃむ アットジャム at jam atjam", "各地"),
        CatalogFest("a-nation", "えーねーしょん エーネーション a-nation anation", "各地スタジアム"),
        CatalogFest("アニメロサマーライブ", "あにめろさまーらいぶ アニサマ animelo summer live anisama", "さいたまスーパーアリーナ"),
        CatalogFest("ラブライブ！フェス", "らぶらいぶふぇす ラブライブフェス lovelive fes", "各地アリーナ")
    ).distinctBy { it.name }

    /** アーティスト → フェス の順で、名前/よみの部分一致で検索。 */
    fun search(q: String, limit: Int = 50): List<SearchHit> {
        val query = q.trim().lowercase()
        if (query.isEmpty()) return emptyList()
        val a = artists.filter { it.name.lowercase().contains(query) || it.kana.lowercase().contains(query) }
            .map { SearchHit.Artist(it) }
        val f = fests.filter { it.name.lowercase().contains(query) || it.kana.lowercase().contains(query) }
            .map { SearchHit.Fest(it) }
        return (a + f).take(limit)
    }
}
