<p align="center">
  <img src="docs/images/banner.jpg" alt="DSH Mobile — आपकी जेब में DeepSeek Harness" width="100%">
</p>

<h1 align="center">DSH Mobile — DeepSeek Harness रिमोट</h1>

<p align="center">
  एक ओपन-सोर्स Android साथी ऐप जो आपके <b>DeepSeek Harness</b> को आपकी जेब में ले आता है।<br>
  सेशन चलाएँ, प्लान और लक्ष्य देखें, अनुमतियों और सवालों का जवाब दें, और harness का काम
  पूरा होते ही सूचना पाएँ — अपने फ़ोन से, अपने लोकल नेटवर्क पर।
</p>

<p align="center">
  <a href="https://dshm.zyphite.com"><img alt="Website" src="https://img.shields.io/badge/website-dshm.zyphite.com-4176E6?style=flat-square"></a>
  <a href="https://github.com/sorsama/deepseek-harness-mobile/releases/latest"><img alt="Latest release" src="https://img.shields.io/github/v/release/sorsama/deepseek-harness-mobile?style=flat-square"></a>
  <a href="https://github.com/sorsama/deepseek-harness-mobile/actions/workflows/ci.yml"><img alt="CI" src="https://img.shields.io/github/actions/workflow/status/sorsama/deepseek-harness-mobile/ci.yml?branch=main&style=flat-square"></a>
  <img alt="Android 8.0+" src="https://img.shields.io/badge/Android-8.0%2B-3DDC84?style=flat-square">
  <a href="LICENSE"><img alt="MIT" src="https://img.shields.io/badge/license-MIT-blue?style=flat-square"></a>
</p>

<p align="center">
  <a href="README.md">English</a> ·
  <a href="README.zh-CN.md">中文</a> ·
  <b>हिन्दी</b> ·
  <a href="README.es.md">Español</a> ·
  <a href="README.fr.md">Français</a> ·
  <a href="README.th.md">ไทย</a>
</p>

DSH Mobile [DeepSeek Harness](https://github.com/deepseek-ai/deepseek-harness) (MIT) के लिए एक
**अनौपचारिक साथी ऐप** है, जो उसके वेब GUI को उसी की दृश्य भाषा में फ़ीचर-दर-फ़ीचर उतारता है।
सिर्फ़ Android, Kotlin + Jetpack Compose।

दूसरे छोर पर इसका साथी है
[**dsh-relay**](https://github.com/sorsama/deepseek-harness-relay) — एक harness प्लगइन, जो वही
प्रमाणीकरण परत जोड़ता है जिसकी कमी harness खुद मानता है, ताकि यह ऐप किसी खुले पोर्ट से नहीं, बल्कि
असली क्रेडेंशियल और पिन की गई key के साथ harness तक पहुँचे। देखें
[Relay](https://github.com/sorsama/deepseek-harness-mobile/wiki/Relay)।

**[dshm.zyphite.com](https://dshm.zyphite.com)** प्रोजेक्ट साइट है — ऐप क्या है, कैसा दिखता है,
और इसे कैसे चलाएँ, सब एक ही पेज पर।

[**wiki**](https://github.com/sorsama/deepseek-harness-mobile/wiki) उपयोगकर्ताओं के लिए गाइड है:
[शुरुआत](https://github.com/sorsama/deepseek-harness-mobile/wiki/Getting-Started),
[कनेक्ट करना](https://github.com/sorsama/deepseek-harness-mobile/wiki/Connecting),
[समस्या-निवारण](https://github.com/sorsama/deepseek-harness-mobile/wiki/Troubleshooting),
एक [फ़ीचर टूर](https://github.com/sorsama/deepseek-harness-mobile/wiki/Feature-Tour) और
[FAQ](https://github.com/sorsama/deepseek-harness-mobile/wiki/FAQ)।

---

## स्क्रीनशॉट

| कनेक्ट | चैट | ट्रैजेक्टरी |
|:--:|:--:|:--:|
| <img src="docs/images/home.png" width="240" alt="कनेक्ट स्क्रीन: हाल के harness, लाइव पहुँच-स्थिति, डिस्कवरी, मैन्युअल एंट्री और ऑटो-कनेक्ट टॉगल"> | <img src="docs/images/chat.png" width="240" alt="चैट: स्ट्रीम होते टर्न, हर टूल का अपना आइकॉन, टूल कार्ड, लक्ष्य डॉक और कम्पोज़र"> | <img src="docs/images/trajectory.png" width="240" alt="ट्रैजेक्टरी: प्रति-टर्न बहीखाता, कुल उपयोग के साथ"> |
| हाल के harness लाइव पहुँच-स्थिति के साथ, LAN डिस्कवरी, मैन्युअल `host:port`, ऑटो-कनेक्ट। | स्ट्रीम होते टर्न, हर टूल के लिए एक ग्लिफ़, खुलने वाले टूल कार्ड, अनुमति चयनकर्ता। | वही सेशन, प्रति-टर्न बहीखाते के रूप में, कुल उपयोग के साथ। |

| सेशन विवरण | उप-एजेंट |
|:--:|:--:|
| <img src="docs/images/session-info.png" width="240" alt="विवरण पैनल: कॉन्टेक्स्ट का ब्यौरा, लक्ष्य, प्लान मोड, कार्य, कतार, उप-एजेंट, होस्ट जानकारी"> | <img src="docs/images/subagent.png" width="240" alt="उप-एजेंट सूची, जिनसे बातचीत जारी रखी जा सकती है"> |
| कॉन्टेक्स्ट का ब्यौरा, लक्ष्य, प्लान मोड, बैकग्राउंड कार्य, कतार में लगे टर्न, होस्ट जानकारी, सेशन-लॉग एक्सपोर्ट। | उप-एजेंट सूची — किसी उप-एजेंट का ट्रांसक्रिप्ट खोलें, आगे पूछें, या उसे रोकें। |

## विशेषताएँ

- **आसान कनेक्शन** — आपके Wi-Fi पर harness अपने-आप ढूँढ़ता है (सक्रिय सबनेट स्कैन +
  रेडीनेस हैंडशेक), पुराने होस्ट याद रखता है और खोलते ही उनकी उपलब्धता जाँचता है, मैन्युअल
  `host:port`, एक ही डिवाइस के लिए लूपबैक, और ऑटो-कनेक्ट टॉगल
  (अंतिम इस्तेमाल / LAN / यही डिवाइस)।
- **Discord जैसा नेविगेशन** — बाएँ किनारे से दाएँ स्वाइप करें और वर्कस्पेस के हिसाब से समूहित
  चैट सूची खुलेगी, बाएँ स्वाइप करने पर बंद; दाएँ किनारे से बाएँ स्वाइप पर सेशन विवरण पैनल।
- **पूरा चैट अनुभव** — स्ट्रीम होते टर्न, खुलने वाला रीज़निंग, markdown,
  टर्मिनल/डिफ़/रीड/सर्च/वेब टूल कार्ड, कतार डॉक (एडिट / हटाएँ / दिशा दें), हिस्ट्री पेजिंग,
  इमेज और फ़ाइल अटैचमेंट।
- **स्लैश कमांड और स्किल** — कम्पोज़र `/` से शुरू होने वाली लाइन को सेशन की अपनी कमांड सूची से
  मिलाता है और उसे harness के कमांड गेटवे से चलाता है; जिसे सूची अपना नहीं मानती वह सामान्य
  प्रॉम्प्ट के रूप में भेजा जाता है — स्किल इसी तरह चलती हैं।
- **जो कुछ GUI करता है, सब** — लक्ष्य (चरण, राउंड, रोकें/जारी रखें/एडिट), प्लान मोड और प्लान
  समीक्षा, अनुमति स्वीकृतियाँ, उपयोगकर्ता से सवाल, टूडू डॉक, उप-एजेंट (सूची, आगे के सवाल,
  रोकना), बैकग्राउंड कार्य, वर्कफ़्लो रन, स्किल, मॉडल चयन, एजेंट प्रीसेट, सेशन खोज,
  ट्रैजेक्टरी बहीखाता, सेशन एक्सपोर्ट, संदेश फ़ीडबैक।
- **सूचनाएँ** — टर्न पूरा, लक्ष्य पूरा / अटका, कोई समीक्षा या सवाल आपका इंतज़ार कर रहा है;
  फ़ोरग्राउंड सर्विस के ज़रिए बैकग्राउंड कनेक्शन।
- **harness जैसा ही दिखता है** — बिल्कुल वही DeepSeek Harness डिज़ाइन टोकन (रंग, टाइपोग्राफ़ी,
  कोने, डिस्क्लोज़र पंक्तियाँ, शिमर, इंक बटन), लाइट / डार्क / सिस्टम थीम के साथ।
- **11 भाषाएँ** — English, 中文, हिन्दी, Español, Français, العربية, বাংলা, Português, Русский,
  اردو, ไทย (RTL सहित)।

## आवश्यकताएँ

- Android 8.0+ (minSdk 26)।
- एक चालू [DeepSeek Harness](https://github.com/deepseek-ai/deepseek-harness)
  (`0.1.3-alpha.1` पर परखा गया)। **0.10.0 के लिए हार्नेस 0.1.3 चाहिए** — उस रिलीज़ ने
  उत्तर के अंशों को लॉग में लिखना बंद कर दिया और उन्हें एक लाइव स्ट्रीम में भेज दिया जिसे ऐप को
  स्वयं माँगना पड़ता है, इसलिए ऐप और हार्नेस दोनों साथ अपडेट होने चाहिए: पुराना ऐप 0.1.3 पर
  बनता हुआ उत्तर कभी नहीं देखता, और यह ऐप 0.1.2 पर स्लैश कमांड नहीं चला सकता।
  देखें [docs/COMPATIBILITY.md](docs/COMPATIBILITY.md)।

## जल्दी शुरू करें

1. नवीनतम APK
   [Releases](https://github.com/sorsama/deepseek-harness-mobile/releases/latest) से इंस्टॉल करें।
2. ऐप खोलें और चुनें कि कैसे कनेक्ट करना है। ये एक ही सेटिंग के रूप नहीं हैं — वही चुनें
   जो आपने कंप्यूटर पर सेट किया है।

   **रिले** — एन्क्रिप्टेड, प्रमाणित, और आपके Wi-Fi के बाहर से भी काम करता है।
   [`dsh-relay`](https://github.com/sorsama/deepseek-harness-relay) को harness की web प्रोफ़ाइल
   में इंस्टॉल करें:

   ```sh
   dsh plugin --profile web add dsh-relay
   dsh web
   ```

   जो URL छपे उसे **उसी कंप्यूटर पर** खोलें, पासवर्ड सेट करें, फिर `/relay/pair` खोलें।
   ऐप में: **रिले → रिले पेयर करें**, और QR स्कैन करें। जब आपके सभी क्लाइंट पेयर हो जाएँ,
   तो रिले का `compat.addressGrants` बंद कर दें — यहाँ किसी को उसकी ज़रूरत नहीं।

   **लोकल नेटवर्क** — फ़ोन पर कोई सेटअप नहीं, और प्रमाणीकरण बिल्कुल भी नहीं।
   [`harness/README.md`](harness/README.md) वाला एक-फ़ाइल LAN पैच लगाएँ, `dsh web` फिर से चालू
   करें, और **नेटवर्क स्कैन करें** पर टैप करें। सिर्फ़ भरोसेमंद नेटवर्क पर।

   **अपने HTTPS रिवर्स प्रॉक्सी के पीछे** — लोकल-नेटवर्क मोड में `https://` पता चिपकाएँ।
   प्रॉक्सी लूपबैक पर आगे भेज सकता है, इसलिए harness को पैच की ज़रूरत नहीं; पर वह लिंक को
   एन्क्रिप्ट भर करता है, किसी की पहचान नहीं जाँचता। देखें
   [`harness/README.md`](harness/README.md)।

   **USB / एमुलेटर** — `dsh web`, फिर `adb reverse tcp:3080 tcp:3080`, और लोकल-नेटवर्क मोड में
   `127.0.0.1:3080` से कनेक्ट करें। कोई पैच नहीं चाहिए।
3. कोई सेशन चुनें, चैट करें, और harness का काम पूरा होने पर सूचना पाएँ।

कनेक्शन विफल होने पर ऐप कारण बता देता है; wiki का
[समस्या-निवारण](https://github.com/sorsama/deepseek-harness-mobile/wiki/Troubleshooting)
पेज ठीक उसी वाक्य के हिसाब से बना है।

## संगतता और सुरक्षा

> **0.1.2:** हार्नेस 0.1.2 से हार्नेस अपने पूरे API को प्रमाणित करता है: ऐप पूछे तो स्टार्टअप पर छपा लिंक एक बार पेस्ट करें। यह फ़ोन को प्रमाणित करता है, पर कनेक्शन को एन्क्रिप्ट नहीं करता — इसलिए केवल भरोसेमंद नेटवर्क पर।

- harness संस्करण मैट्रिक्स और केवल-लूपबैक सतहों के लिए
  [docs/COMPATIBILITY.md](docs/COMPATIBILITY.md) देखें।
- **पहले [docs/SECURITY.md](docs/SECURITY.md) पढ़ें।** सादे harness में कोई प्रमाणीकरण नहीं है,
  इसलिए लोकल-नेटवर्क मोड सिर्फ़ भरोसेमंद नेटवर्क के लिए है — इसी वजह से ऐप कनेक्ट स्क्रीन पर यही
  चेतावनी दिखाता है। रिले मोड असली क्रेडेंशियल और पिन किया हुआ सर्टिफ़िकेट जोड़ता है, लेकिन
  प्रमाणित हो जाने पर भी उतनी ही ताक़त मिलती है जितनी उस कंप्यूटर पर shell खोलने से, क्योंकि
  एजेंट कमांड वहीं चलाता है।

## बिल्ड करना

```sh
./gradlew :app:assembleDebug      # डीबग APK
./gradlew :app:assembleRelease    # रिलीज़ APK (keystore एनवायरनमेंट सेट होने पर साइन किया हुआ)
```

रिलीज़ का संस्करण git टैग से आता है: रिलीज़ वर्कफ़्लो टैग के नाम से `DSH_VERSION_NAME` निर्यात
करता है, और `versionCode` उसी से बनता है। लोकल बिल्ड `app/build.gradle.kts` में लिखे मान पर
वापस चला जाता है।

असली harness के साथ डेवलपमेंट लूप, मॉड्यूल संरचना और रिलीज़ वर्कफ़्लो के लिए
[CONTRIBUTING.md](CONTRIBUTING.md) देखें।

## रिपॉज़िटरी

| पथ | क्या है |
|---|---|
| `core/` | शुद्ध JVM प्रोटोकॉल कोर: वायर DTO, RPC क्लाइंट, WebSocket डाउनलिंक, री-कनेक्ट लूप, सेशन फ़ोल्डिंग, नोटिफ़िकेशन क्लासिफ़ायर |
| `app/` | Android UI: स्क्रीन, डिस्कवरी/कनेक्शन, फ़ोरग्राउंड सर्विस, सूचनाएँ, i18n |
| `mock-harness/` | टेस्ट के लिए harness `/api` सर्वर का Ktor मॉक |
| `tools/capture/` | असली harness ट्रैफ़िक को कन्फ़ॉर्मेंस फ़िक्स्चर में रिकॉर्ड करता है |
| `harness/` | LAN मोड के लिए साथी पैच और गाइड |
| — | रिले खुद [sorsama/deepseek-harness-relay](https://github.com/sorsama/deepseek-harness-relay) में रहता है |
| `docs/` | [आर्किटेक्चर](docs/ARCHITECTURE.md), [प्रोटोकॉल नोट्स](docs/PROTOCOL.md), [संगतता](docs/COMPATIBILITY.md), [सुरक्षा](docs/SECURITY.md) |

## लाइसेंस

[MIT](LICENSE)। साथ में दी गई तीसरे पक्ष की सामग्री
[THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md) में सूचीबद्ध है। DeepSeek Harness और उसका ब्रांड
उनके संबंधित स्वामियों की संपत्ति हैं; यह प्रोजेक्ट एक स्वतंत्र, समुदाय-निर्मित रिमोट है।
