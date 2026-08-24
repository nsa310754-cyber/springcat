/**
 * X 公式 API が未設定のときに使うサンプル投稿。
 * 実 API のレスポンス（tweets/search/recent）を簡略化した形に揃えている。
 * これにより API キーが無くても検索→AI判定→一覧→DM生成の一連を体験できる。
 */
export const mockTweets = [
  {
    id: "1001",
    text: "今日のUber Eats全然鳴らない…単価も下がったしフーデリだけで食べていくの正直きつい。大阪もっと稼げるとこないかな",
    createdAt: "2026-08-23T10:12:00Z",
    author: {
      id: "u1",
      username: "osaka_haitatsu",
      name: "配達マン@大阪",
      description: "大阪でUber Eats専業3年目｜梅田・難波中心｜副業から専業に｜フーデリの日常つぶやきます",
      location: "大阪",
      followers: 820,
    },
  },
  {
    id: "1002",
    text: "出前館の報酬体系また改悪された。単価低いしこれじゃやってられん。東京23区でおすすめのデリバリー教えてほしい",
    createdAt: "2026-08-23T08:45:00Z",
    author: {
      id: "u2",
      username: "tokyo_delivery_k",
      name: "けん｜フードデリバリー",
      description: "東京在住｜出前館・Uberかけもち｜23区内で配達｜稼ぐ工夫を発信",
      location: "東京都",
      followers: 3400,
    },
  },
  {
    id: "1003",
    text: "神奈川エリア、雨の日は鳴るけど平常時は本当に鳴らない。ロケットナウ気になってるけど実際どうなんだろう？",
    createdAt: "2026-08-22T19:30:00Z",
    author: {
      id: "u3",
      username: "kanagawa_rider",
      name: "よこはまライダー",
      description: "横浜で配達員やってます｜自転車配達｜フーデリ歴1年",
      location: "横浜",
      followers: 150,
    },
  },
  {
    id: "1004",
    text: "今日は快晴☀️ 犬の散歩なう。うちのトイプードルかわいすぎる🐩",
    createdAt: "2026-08-22T14:00:00Z",
    author: {
      id: "u4",
      username: "wanko_daisuki",
      name: "ぽち",
      description: "犬とカフェが好き｜日常アカウント",
      location: "福岡",
      followers: 500,
    },
  },
  {
    id: "1005",
    text: "配達員始めて半年。Uber Eats単価低いって聞くけど掛け持ちすればなんとかなる。名古屋でフーデリ仲間募集中！",
    createdAt: "2026-08-21T21:10:00Z",
    author: {
      id: "u5",
      username: "nagoya_food_r",
      name: "なごや配達部",
      description: "名古屋｜Uber・Wolt掛け持ち｜フードデリバリー情報共有",
      location: "愛知県名古屋市",
      followers: 1250,
    },
  },
  {
    id: "1006",
    text: "最新のiPhone予約した。カメラ楽しみすぎる📱",
    createdAt: "2026-08-21T11:00:00Z",
    author: {
      id: "u6",
      username: "gadget_suki",
      name: "ガジェット好き",
      description: "ガジェットレビュー｜ノマドワーカー",
      location: "東京",
      followers: 9800,
    },
  },
  {
    id: "1007",
    text: "出前館もUberも鳴らなさすぎて泣ける。専業なのにこの時給はやばい。埼玉で稼げるデリバリーサービスまじで知りたい",
    createdAt: "2026-08-20T18:20:00Z",
    author: {
      id: "u7",
      username: "saitama_haitatu",
      name: "さいたま配達員",
      description: "埼玉南部で活動｜バイク配達｜専業フーデリ｜家族持ち",
      location: "埼玉県",
      followers: 640,
    },
  },
  {
    id: "1008",
    text: "ロケットナウ始めてみたら意外と鳴る。Uber Eatsの合間にやると単価補える感じ。大阪だとどのエリアが強いんだろ",
    createdAt: "2026-08-20T09:05:00Z",
    author: {
      id: "u8",
      username: "delivery_osaka2",
      name: "配達ちゃん",
      description: "大阪市内｜フードデリバリー掛け持ち勢｜稼ぎ方研究中",
      location: "大阪市",
      followers: 2100,
    },
  },
];
