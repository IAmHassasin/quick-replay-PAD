Monsters in file: 14338; with a leader skill: 9644; selected: 5000 (No. 5601..14303); distinct leader skills: 4400

| Style | Leader skills | Monsters | Parameters (skills) | Support |
|---|---:|---:|---|---|
| N+ orbs of one colour connected (`blob_min`) | 1222 | 1238 | N=6 (one colour): 615; N=5 (one colour): 578; N=4 (one colour): 237; N=9 (one colour): 114; N=5 (any colour): 80; N=6 (two colours together): 64 | unsupported |
| A fixed set of colours matched at once (`colors_at_once`) | 892 | 905 | R+B: 100; R+D: 98; R+L: 94; L+D: 77; B+D: 73; B+H: 68 | unsupported |
| N+ separate combos of one colour (`same_color_combos`) | 856 | 865 | 2 x D: 413; 2 x R: 285; 2 x L: 234; 2 x G: 185; 2 x B: 179; 2 x H: 86 | unsupported |
| K+ colours matched at once (of a set) (`rainbow`) | 779 | 793 | K=3 of any: 424; K=4 of any: 348; K=5 of any: 172; K=2 of any: 53; K=6 of any+H (heart in set): 29; K=5 of RBGLD: 24 | unsupported |
| N+ combos (any colours) (`combos`) | 638 | 652 | N=7: 245; N=8: 177; N=10: 93; N=6: 91; N=9: 67; N=5: 52 | supported |
| Heart match (4 hearts / hearts in a set) (`heal_match`) | 349 | 354 | B+H: 68; L+H: 65; G+H: 61; R+H: 58; cross of 5 hearts: 46; D+H: 45 | partial |
| Exactly 4 orbs (two-pronged attack) (`tpa`) | 170 | 170 | own: 111; any+H: 14; G: 8; L: 5; LD: 4; BG: 4 | supported |
| L of 5 (`lshape`) | 125 | 126 | own: 50; any+H: 48; B: 24; R: 5; LD: 4; H: 4 | supported |
| Exactly N orbs of one colour (N != 4) (`blob_exact`) | 114 | 115 | N=5: 79; N=6: 24; N=9: 8; N=10: 3 | unsupported |
| Cross of 5 (colour) (`cross`) | 87 | 89 | own: 40; RD: 14; LD: 8; R: 7; RBG: 6; D: 6 | supported |
| 3x3 square (void damage piercer) (`square`) | 66 | 66 | own: 66 | supported |
| Full row of one colour (`row`) | 63 | 64 | D: 28; R: 17; L: 12; B: 7; G: 6; H: 1 | supported |
| Cross of 5 hearts (`heart_cross`) | 28 | 30 | -: 46 | supported |
| T of 5 (`tshape`) | 24 | 24 | own: 24 | unsupported |
| Full column of one colour (`column`) | 14 | 14 | G: 6; D: 6; B: 2 | supported |
| 5 orbs including an enhanced orb (`enhanced5`) | 12 | 13 | -: 12 | unsupported |
| Mixed combo pattern (repeated colour + other colours) (`combo_pattern`) | 12 | 12 | 4 of D+D+R+R: 4; 3 of L+L+B: 4; 3 of L+L+D: 3; 3 of B+B+L: 3; 4 of D+D+B+B: 2; 3 of B+B+L+L: 2 | unsupported |
| Cannot erase N or fewer orbs (every match must be larger) (`min_erase`) | 9 | 9 | N=4: 7; N=5: 2 | unsupported |
| Exactly N combos (`combos_exact`) | 3 | 3 | N=7: 2; N=10: 1 | unsupported |

### Examples

**N+ orbs of one colour connected** (`blob_min`)

- No. 14300 ダークグレープマン (skill 66377, 1 monsters): 闇を5個つなげて消すとダメージを半減、攻撃力が20倍、 4コンボ加算。闇属性の全パラメータが2.7倍。
- No. 14298 シナモロールガチャドラ (skill 66385, 1 monsters): 光を9個つなげて消すと攻撃力が10倍、固定2000万 ダメージ。[ドラゴンタイプ]の全パラメータが5.5倍。
- No. 14252 王威の親子神・オシリス＆ホルス (skill 66150, 1 monsters): 火か木を5個つなげて消すとダメージを82％軽減。 [木火同時攻撃]発動で攻撃力が130倍、回復力が2倍、3コンボ加算。

**A fixed set of colours matched at once** (`colors_at_once`)

- No. 14301 転生ポチャッコ (skill 66338, 1 monsters): 火回復の同時攻撃でダメージを82％軽減。[火コンボ強化]発動で 攻撃力が120倍、4コンボ加算、固定1000万ダメージ。
- No. 14299 ダイヤシナモロールガチャドラ (skill 66394, 1 monsters): 【操作時間12秒】 光回復の同時攻撃でダメージを軽減、 攻撃力が18倍、4コンボ加算。[ドラゴンタイプ]の全パラメータが3.6倍。
- No. 14296 メティスまるもふびより (skill 66235, 1 monsters): 水回復の同時攻撃でダメージを83％軽減。[水木同時攻撃]発動で 攻撃力が150倍、4コンボ加算、固定1200万ダメージ。

**N+ separate combos of one colour** (`same_color_combos`)

- No. 14303 転生ポムポムプリン (skill 66330, 1 monsters): 【落ちコンなし】 [光コンボ強化]発動でダメージを激減、攻撃力が 90倍、5コンボ加算。光属性の全パラメータが1.4倍。
- No. 14302 転生シナモロール (skill 66321, 1 monsters): [光コンボ強化]発動でダメージを軽減、攻撃力が120倍、回復力が8倍、 3コンボ加算、固定2000万ダメージ。光属性のHPが3.6倍。
- No. 14301 転生ポチャッコ (skill 66338, 1 monsters): 火回復の同時攻撃でダメージを82％軽減。[火コンボ強化]発動で 攻撃力が120倍、4コンボ加算、固定1000万ダメージ。

**K+ colours matched at once (of a set)** (`rainbow`)

- No. 14282 シナモロールとフレンズ (skill 66278, 1 monsters): 光回復の同時攻撃でダメージを82％軽減、回復力が3倍。 [光コンボ強化]発動で攻撃力が120倍、4コンボ加算、固定1000万ダメージ。
- No. 14210 嵐操龍・ミストール (skill 65467, 1 monsters): 【落ちコンなし】 4色同時攻撃でダメージを激減。[5色攻撃強化]発動で 攻撃力が180倍、5コンボ加算。[ドラゴンタイプ]のHPが1.4倍。
- No. 14202 陶酔の仮装祭・テキーラギムレット (skill 65864, 1 monsters): 【落ちコンなし】 3色同時攻撃でダメージを82％軽減。 [火水同時攻撃]発動で攻撃力が130倍、固定3000万ダメージ。

**N+ combos (any colours)** (`combos`)

- No. 14280 クロミとバク (skill 66266, 1 monsters): 7コンボでダメージを半減。[木縦消し攻撃]発動で攻撃力が100倍、 回復力が8倍、3コンボ加算。木と闇属性のHPが2.8倍。
- No. 14255 闇翼の蝙蝠帝・ノスフェラード (skill 66180, 1 monsters): [闇縦消し攻撃]発動でダメージを83％軽減、5コンボ加算。 15コンボで攻撃力が150倍。[マシンタイプ]に[バインド耐性+]を付与。
- No. 14208 メイド・ヴァルキリー＆執事・ヴァンパイアデューク (skill 65985, 1 monsters): 光属性のHPと回復力が2.8倍。7コンボでダメージを半減。 [光コンボ強化]発動で攻撃力が120倍、4コンボ加算、固定1000万ダメージ。

**Heart match (4 hearts / hearts in a set)** (`heal_match`)

- No. 14301 転生ポチャッコ (skill 66338, 1 monsters): 火回復の同時攻撃でダメージを82％軽減。[火コンボ強化]発動で 攻撃力が120倍、4コンボ加算、固定1000万ダメージ。
- No. 14299 ダイヤシナモロールガチャドラ (skill 66394, 1 monsters): 【操作時間12秒】 光回復の同時攻撃でダメージを軽減、 攻撃力が18倍、4コンボ加算。[ドラゴンタイプ]の全パラメータが3.6倍。
- No. 14296 メティスまるもふびより (skill 66235, 1 monsters): 水回復の同時攻撃でダメージを83％軽減。[水木同時攻撃]発動で 攻撃力が150倍、4コンボ加算、固定1200万ダメージ。

**Exactly 4 orbs (two-pronged attack)** (`tpa`)

- No. 14293 ゼウスしろうさ＆ヘラくろうさ（シュガーバニーズ） (skill 66356, 1 monsters): [2体攻撃]発動でダメージを82％軽減、攻撃力が80倍、3コンボ 加算、固定2000万ダメージ。HP満タン時に攻撃力が3倍。
- No. 14290 ニケウサハナ (skill 66253, 1 monsters): [2体攻撃]発動でダメージを82％軽減、攻撃力が100倍、 2コンボ加算、固定2000万ダメージ。
- No. 14220 極醒の葵風龍喚士・カエデ (skill 65961, 1 monsters): 闇回復の同時攻撃でダメージを半減。[2体攻撃]発動で攻撃力が 100倍、5コンボ加算。闇属性のHPが2.8倍、回復力が5倍。

**L of 5** (`lshape`)

- No. 14187 九尾の仮装夜行・リュウメイ (skill 65821, 1 monsters): 光を5個つなげて消すとダメージを83％軽減。 [L字消し攻撃]発動で攻撃力が200倍、5コンボ加算。
- No. 14141 耀雪の雪女・ユキネ (skill 65457, 1 monsters): 【7×6マス】 [L字消し攻撃]発動でダメージを81％軽減、攻撃力が60倍、 回復力が2倍、2コンボ加算、固定1500万ダメージ。
- No. 13996 L.L.＆C.C. (skill 64282, 1 monsters): 木を5個つなげて消すとダメージを82％軽減。[L字消し攻撃]発動で攻撃力が 100倍、回復力が2倍、3コンボ加算、固定1800万ダメージ。

**Exactly N orbs of one colour (N != 4)** (`blob_exact`)

- No. 12972 夕饗の神王妃・ヘラ -LUNA- (skill 57223, 1 monsters): 火闇回復の同時攻撃でダメージを82％軽減、攻撃力が36倍。 ドロップの5個消し1個につき攻撃力と回復力が1.3倍、1コンボ加算。
- No. 12872 聖人会議長ラウフェイ (skill 56532, 1 monsters): 9コンボでダメージを81％軽減、攻撃力が50倍、固定900万 ダメージ。水の5個消し1個につき回復力が1.5倍、2コンボ加算。
- No. 12264 玄武星の超越神・メイメイ (skill 51331, 1 monsters): 木、光、闇の5個消し1個につき攻撃力が4倍、1コンボ加算、 固定400万ダメージ。[神タイプ]のHPと回復力が5.3倍。

**Cross of 5 (colour)** (`cross`)

- No. 14118 トランプ迷宮の主・ドラウンジョーカー (skill 65275, 1 monsters): [十字消し攻撃][闇コンボ強化]の同時発動でダメージを半減、攻撃力が180倍、 2コンボ加算、固定2800万ダメージ。[悪魔タイプ]のHPが2.8倍。
- No. 14012 端居の箏龍楽士・ミナカ (skill 64481, 1 monsters): [十字消し攻撃]発動でダメージを激減、攻撃力が150倍、4コンボ加算、 固定1000万ダメージ。火属性のHPが1.3倍、回復力が5倍。
- No. 14008 バカンスの女神・ヘスティア (skill 64436, 1 monsters): 【操作時間12秒】 [十字消し攻撃]発動でダメージを83％軽減、 攻撃力が150倍、3コンボ加算、固定2000万ダメージ。

**3x3 square (void damage piercer)** (`square`)

- No. 13872 潤香執事・カシアン (skill 63290, 1 monsters): 【7×6マス】 闇属性の全パラメータが5.3倍。 [ダメージ無効貫通攻撃]を発動するたびに攻撃力が7倍、固定1000万ダメージ。
- No. 13848 モーニングコールメイド・テミス (skill 63053, 1 monsters): [ダメージ無効貫通攻撃]発動でダメージを22％軽減、攻撃力が33倍、 固定3333万ダメージ。[バランスタイプ]の全パラメータが4.4倍。
- No. 13844 アイロン掛けのメイド・フリッグ (skill 63092, 1 monsters): 木を5個つなげて消すとダメージを82％軽減。 [ダメージ無効貫通攻撃]発動で攻撃力が100倍、回復力が2倍、5コンボ加算。

**Full row of one colour** (`row`)

- No. 14110 ハート龍・セイハーツ (skill 65201, 1 monsters): 火を5個つなげて消すとダメージを82％軽減。[火列強化]発動で 攻撃力が150倍、3コンボ加算、固定2200万ダメージ。
- No. 14101 【ハートQ】の女王・ハーティア (skill 65120, 1 monsters): [火列強化]発動でダメージを激減、攻撃力が150倍、回復力が2倍、 3コンボ加算、固定2500万ダメージ。火属性のHPが1.4倍。
- No. 14072 バンビエッタ・バスターバイン (skill 64886, 1 monsters): [火列強化]発動でダメージを半減、攻撃力が80倍、 5コンボ加算。火属性のHPが2.7倍、回復力が9倍。

**Cross of 5 hearts** (`heart_cross`)

- No. 13909 極醒の黄龍契士・シルヴィ (skill 63697, 1 monsters): 回復の5個十字消しでダメージを82％軽減、回復力が3倍。 [2体攻撃]発動で攻撃力が120倍、3コンボ加算、固定1800万ダメージ。
- No. 13838 ぐうたらメイド・アマテラスオオカミ (skill 63065, 1 monsters): 回復の5個十字消しでダメージを半減、攻撃力が90倍、回復力が 9倍。8コンボで固定3000万ダメージ。光属性のHPが2.8倍。
- No. 13834 時間厳守のメイド・ミル (skill 63039, 1 monsters): 回復の5個十字消しでダメージを83％軽減。[2体攻撃]発動で 攻撃力が100倍、3コンボ加算、固定2000万ダメージ。

**T of 5** (`tshape`)

- No. 14113 ダイヤ龍・ダイアモス (skill 65216, 1 monsters): 光を5個つなげて消すとダメージを82％軽減。[T字消し攻撃]発動で 攻撃力が170倍、2コンボ加算、固定3000万ダメージ。
- No. 14098 【ダイヤK】の王者・ダイン (skill 65153, 1 monsters): 光を5個つなげて消すとダメージを83％軽減。[T字消し攻撃]発動で 攻撃力が170倍、4コンボ加算、固定1400万ダメージ。
- No. 14092 聖文字“B”・ユーグラム・ハッシュヴァルト (skill 64955, 1 monsters): [T字消し攻撃]発動でダメージを82％軽減、攻撃力が120倍、3コンボ 加算、固定1800万ダメージ。光属性の回復力が2倍。

**Full column of one colour** (`column`)

- No. 14280 クロミとバク (skill 66266, 1 monsters): 7コンボでダメージを半減。[木縦消し攻撃]発動で攻撃力が100倍、 回復力が8倍、3コンボ加算。木と闇属性のHPが2.8倍。
- No. 14255 闇翼の蝙蝠帝・ノスフェラード (skill 66180, 1 monsters): [闇縦消し攻撃]発動でダメージを83％軽減、5コンボ加算。 15コンボで攻撃力が150倍。[マシンタイプ]に[バインド耐性+]を付与。
- No. 14246 楼蘭 (skill 66091, 1 monsters): 【操作時間12秒】 闇属性のHPと回復力が5.6倍。 [闇縦消し攻撃]発動で攻撃力が100倍、回復力が3倍、4コンボ加算。

**5 orbs including an enhanced orb** (`enhanced5`)

- No. 11638 深紅の宝石姫・シルクチャーミーキティ (skill 46839, 2 monsters): 【7×6マス】 7コンボでダメージを77％軽減。 強化ドロップを含めて5個消した属性の攻撃力が40倍。
- No. 10321 ウルトラマンレグロス(アーリースタイル) (skill 37690, 1 monsters): 火属性のHPが4.8倍。火の2コンボで4コンボ加算。 強化ドロップを含めて5個消した属性の攻撃力が40倍。
- No. 9249 山の女神パールヴァティー (skill 31490, 1 monsters): 3色同時攻撃でダメージを65％軽減、3コンボ加算。 強化ドロップを含めて5個消した属性の攻撃力が22倍。

**Mixed combo pattern (repeated colour + other colours)** (`combo_pattern`)

- No. 12008 U-20日本代表・士道 龍聖 (skill 49800, 1 monsters): 闇闇火火の同時攻撃でダメージを80％軽減、攻撃力が50倍、 5コンボ加算。[攻撃タイプ]に[バインド耐性+]を付与。
- No. 12007 士道 龍聖 (skill 49796, 1 monsters): 闇闇火火の同時攻撃でダメージを激減、攻撃力が30倍、 2コンボ加算。[攻撃タイプ]に[バインド耐性+]を付与。
- No. 11507 シャガルマガラ (skill 46217, 1 monsters): 光光闇の同時攻撃でダメージを80％軽減、攻撃力が45倍、 3コンボ加算、固定500万ダメージ。

**Cannot erase N or fewer orbs (every match must be larger)** (`min_erase`)

- No. 9927 冥界龍・ハーデス＝ドラゴン (skill 35102, 1 monsters): 闇を9個つなげて消すと攻撃力が40倍、3コンボ加算、 固定1ダメージ。 ドロップを3個以下で消せない がHPが5倍。
- No. 9884 闇遊戯＆三幻神 (skill 34945, 1 monsters): ドロップを3個以下で消せない が、全パラメータが5倍。 闇を5個つなげて消すと攻撃力が5倍。操作時間が3秒延長。
- No. 9825 冥翔の大魔女・ネレ (skill 35114, 1 monsters): 【7×6マス】ドロップを3個以下で消せない がHPが4.2倍。 闇を6個つなげて消すと攻撃力が25倍。

**Exactly N combos** (`combos_exact`)

- No. 7791 昂陽の炎柱神・アメン (skill 23564, 1 monsters): 【落ちコンなし／操作時間7秒】 火属性のHPと回復力が5倍。 10コンボちょうどで攻撃力が500倍。
- No. 6384 大罪の化身・ゴルフェイス (skill 18065, 1 monsters): 【落ちコンなし】 7コンボちょうどで攻撃力が2倍。 総HPが半減、光属性の攻撃力が12倍。
- No. 5847 滅亡の化身・ゴルケイオス (skill 18057, 1 monsters): 【落ちコンなし】 7コンボちょうどで攻撃力が2倍。 総HPが半減、闇属性の攻撃力が13倍。

| Other condition | Leader skills | Monsters |
|---|---:|---:|
| passive stats | 2114 | 2688 |
| 7x6 board | 459 | 468 |
| extra move time | 332 | 340 |
| fixed move time | 284 | 286 |
| team composition | 177 | 178 |
| HP threshold | 130 | 134 |
| rank exp | 114 | 116 |
| drop rate | 115 | 115 |
| no skyfall | 70 | 70 |
| poison disabled | 47 | 48 |
| skill used | 44 | 47 |
| grants awakening | 45 | 45 |
| no skyfall / orbs remaining | 37 | 38 |
| autoheal | 33 | 33 |
| coin rate | 29 | 30 |
| heal amount | 21 | 23 |
| drop/plus rate | 18 | 18 |
| skyfall prediction | 17 | 17 |
| skills used per turn | 15 | 15 |
| resolve | 7 | 7 |
| reduced HP | 6 | 6 |
| counterattack | 5 | 5 |
| reduced RCV | 1 | 1 |

unmapped: {}
