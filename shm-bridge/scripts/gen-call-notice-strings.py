#!/usr/bin/env python3
"""Generates res/values*/call_notice.xml in shm-bridge/watch and shm-bridge/phone, and
res/values*/alarm_label.xml (label of the phone alarm in the Samsung alarm app) in watch.

"Call handed over to the phone" notice: the watch dialed over HFP, the phone did not open
the audio in time (typical: it asks which SIM to use for every call) and the Samsung HFP
client closed its side with DisconnectCause CALL_PULLED. The locales are those of the
watch system apps. Translations were not reviewed by native speakers.

Usage: python3 shm-bridge/scripts/gen-call-notice-strings.py
"""
import os

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))

# (qualifier) -> (channel, title, text). "" = values/ (English, fallback language).
S = {
    "": ("Call notices", "Call moved to your phone",
         "The watch didn't get the call audio in time, so the call stayed on your phone. This usually happens when the phone asks which SIM to use for each call. Set a default SIM for calls on your phone."),
    "es": ("Avisos de llamadas", "La llamada pasó al teléfono",
           "El reloj no recibió el audio de la llamada a tiempo, así que la llamada quedó en el teléfono. Suele pasar cuando el teléfono pide elegir la SIM en cada llamada. Configura una SIM predeterminada para llamadas en el teléfono."),
    "es-rAR": ("Avisos de llamadas", "La llamada pasó al teléfono",
               "El reloj no recibió el audio de la llamada a tiempo, así que la llamada quedó en el teléfono. Suele pasar cuando el teléfono pide elegir la SIM en cada llamada. Configurá una SIM predeterminada para llamadas en el teléfono."),
    "af": ("Oproepkennisgewings", "Oproep is na jou foon geskuif",
           "Die horlosie het nie die oproepklank betyds ontvang nie, dus het die oproep op jou foon gebly. Dit gebeur gewoonlik wanneer die foon by elke oproep vra watter SIM om te gebruik. Stel 'n verstek-SIM vir oproepe op jou foon."),
    "am": ("የጥሪ ማሳወቂያዎች", "ጥሪው ወደ ስልክዎ ተዛውሯል",
           "ሰዓቱ የጥሪውን ድምፅ በጊዜ ስላላገኘ ጥሪው በስልክዎ ላይ ቀርቷል። ይህ ብዙውን ጊዜ ስልኩ ለእያንዳንዱ ጥሪ የትኛውን ሲም እንደሚጠቀም ሲጠይቅ ይከሰታል። በስልክዎ ላይ ለጥሪዎች ነባሪ ሲም ያዘጋጁ።"),
    "ar": ("إشعارات المكالمات", "انتقلت المكالمة إلى هاتفك",
           "لم تتلقَّ الساعة صوت المكالمة في الوقت المناسب، لذا بقيت المكالمة على هاتفك. يحدث هذا عادةً عندما يسأل الهاتف عن شريحة SIM التي يجب استخدامها في كل مكالمة. عيِّن شريحة SIM افتراضية للمكالمات على هاتفك."),
    "as": ("কলৰ জাননী", "কলটো আপোনাৰ ফোনলৈ স্থানান্তৰিত হ'ল",
           "ঘড়ীটোৱে সময়মতে কলৰ অডিঅ' নাপালে, সেয়ে কলটো আপোনাৰ ফোনতে থাকিল। ফোনটোৱে প্ৰতিটো কলৰ বাবে কোনটো ছিম ব্যৱহাৰ কৰিব সুধিলে সাধাৰণতে এনে হয়। আপোনাৰ ফোনত কলৰ বাবে এটা ডিফল্ট ছিম ছেট কৰক।"),
    "az": ("Zəng bildirişləri", "Zəng telefonunuza keçdi",
           "Saat zəng səsini vaxtında almadı, ona görə də zəng telefonunuzda qaldı. Bu, adətən telefon hər zəng üçün hansı SIM-in istifadə olunacağını soruşduqda baş verir. Telefonunuzda zənglər üçün defolt SIM təyin edin."),
    "be": ("Апавяшчэнні пра выклікі", "Выклік перададзены на тэлефон",
           "Гадзіннік не атрымаў гук выкліку своечасова, таму выклік застаўся на тэлефоне. Звычайна гэта адбываецца, калі тэлефон пры кожным выкліку пытаецца, якую SIM-карту выкарыстоўваць. Задайце на тэлефоне SIM-карту па змаўчанні для выклікаў."),
    "bg": ("Известия за обаждания", "Обаждането премина към телефона ви",
           "Часовникът не получи звука от обаждането навреме, затова обаждането остана на телефона ви. Това обикновено се случва, когато телефонът пита коя SIM карта да използва при всяко обаждане. Задайте SIM карта по подразбиране за обаждания на телефона си."),
    "bn": ("কলের বিজ্ঞপ্তি", "কলটি আপনার ফোনে চলে গেছে",
           "ঘড়িটি সময়মতো কলের অডিও পায়নি, তাই কলটি আপনার ফোনেই রয়ে গেছে। ফোন যখন প্রতিটি কলের জন্য কোন সিম ব্যবহার করবে তা জিজ্ঞাসা করে, তখন সাধারণত এমন হয়। আপনার ফোনে কলের জন্য একটি ডিফল্ট সিম সেট করুন।"),
    "bo": ("ཁ་པར་གྱི་བརྡ་ཐོ།", "ཁ་པར་ཁྱེད་ཀྱི་ཁ་པར་ཐོག་སྤོས་ཟིན།",
           "ལག་ཆུ་ཚོད་ཀྱིས་དུས་ཐོག་ཏུ་ཁ་པར་གྱི་སྒྲ་མ་ཐོབ་པས། ཁ་པར་དེ་ཁྱེད་ཀྱི་ཁ་པར་ཐོག་ལུས་ཡོད། ཁ་པར་གྱིས་ཁ་པར་རེ་རེར་ SIM གང་བེད་སྤྱོད་བྱེད་དམ་དྲི་བའི་སྐབས་ཕལ་ཆེར་འདི་ལྟར་འབྱུང་། ཁྱེད་ཀྱི་ཁ་པར་ཐོག་ཁ་པར་གཏོང་བར་སྔོན་སྒྲིག་ SIM ཞིག་སྒྲིག་རོགས།"),
    "bs": ("Obavještenja o pozivima", "Poziv je prebačen na telefon",
           "Sat nije na vrijeme primio zvuk poziva, pa je poziv ostao na vašem telefonu. To se obično dešava kada telefon za svaki poziv pita koju SIM karticu koristiti. Postavite zadanu SIM karticu za pozive na telefonu."),
    "ca": ("Avisos de trucades", "La trucada ha passat al telèfon",
           "El rellotge no ha rebut l'àudio de la trucada a temps i la trucada s'ha quedat al telèfon. Sol passar quan el telèfon demana quina SIM cal fer servir a cada trucada. Configura una SIM predeterminada per a les trucades al telèfon."),
    "cs": ("Upozornění na hovory", "Hovor se přesunul do telefonu",
           "Hodinky nedostaly zvuk hovoru včas, takže hovor zůstal v telefonu. Obvykle se to stane, když se telefon u každého hovoru ptá, kterou SIM kartu použít. Nastavte v telefonu výchozí SIM kartu pro hovory."),
    "da": ("Opkaldsmeddelelser", "Opkaldet blev flyttet til din telefon",
           "Uret modtog ikke lyden fra opkaldet i tide, så opkaldet blev på din telefon. Det sker typisk, når telefonen spørger, hvilket SIM-kort der skal bruges ved hvert opkald. Vælg et standard-SIM-kort til opkald på din telefon."),
    "de": ("Anrufhinweise", "Anruf wurde aufs Telefon verlegt",
           "Die Uhr hat den Anrufton nicht rechtzeitig erhalten, daher ist der Anruf auf deinem Telefon geblieben. Das passiert meist, wenn das Telefon bei jedem Anruf fragt, welche SIM verwendet werden soll. Lege auf deinem Telefon eine Standard-SIM für Anrufe fest."),
    "el": ("Ειδοποιήσεις κλήσεων", "Η κλήση μεταφέρθηκε στο τηλέφωνο",
           "Το ρολόι δεν έλαβε εγκαίρως τον ήχο της κλήσης, οπότε η κλήση παρέμεινε στο τηλέφωνό σας. Αυτό συμβαίνει συνήθως όταν το τηλέφωνο ρωτά ποια SIM να χρησιμοποιήσει σε κάθε κλήση. Ορίστε προεπιλεγμένη SIM για κλήσεις στο τηλέφωνό σας."),
    "et": ("Kõneteated", "Kõne liikus telefoni",
           "Kell ei saanud kõne heli õigel ajal kätte, seega jäi kõne telefoni. Tavaliselt juhtub see siis, kui telefon küsib iga kõne puhul, millist SIM-kaarti kasutada. Määrake telefonis kõnede jaoks vaikimisi SIM-kaart."),
    "eu": ("Deien jakinarazpenak", "Deia telefonora pasatu da",
           "Erlojuak ez du deiaren audioa garaiz jaso, eta deia telefonoan geratu da. Telefonoak dei bakoitzean zein SIM erabili galdetzen duenean gertatu ohi da. Ezarri deietarako SIM lehenetsi bat telefonoan."),
    "fa": ("اعلان‌های تماس", "تماس به تلفن شما منتقل شد",
           "ساعت صدای تماس را به‌موقع دریافت نکرد، بنابراین تماس روی تلفن شما ماند. این معمولاً وقتی رخ می‌دهد که تلفن برای هر تماس می‌پرسد از کدام سیم‌کارت استفاده شود. در تلفن خود یک سیم‌کارت پیش‌فرض برای تماس‌ها تنظیم کنید."),
    "fi": ("Puheluilmoitukset", "Puhelu siirtyi puhelimeen",
           "Kello ei saanut puhelun ääntä ajoissa, joten puhelu jäi puhelimeesi. Näin käy yleensä, kun puhelin kysyy jokaisen puhelun kohdalla, mitä SIM-korttia käytetään. Valitse puhelimessa oletus-SIM-kortti puheluille."),
    "fr": ("Avis d'appel", "Appel transféré sur le téléphone",
           "La montre n'a pas reçu l'audio de l'appel à temps, l'appel est donc resté sur votre téléphone. Cela arrive généralement lorsque le téléphone demande quelle carte SIM utiliser pour chaque appel. Définissez une carte SIM par défaut pour les appels sur votre téléphone."),
    "ga": ("Fógraí glaonna", "Aistríodh an glao chuig do ghuthán",
           "Ní bhfuair an uaireadóir fuaim an ghlao in am, mar sin d'fhan an glao ar do ghuthán. Tarlaíonn sé seo de ghnáth nuair a fhiafraíonn an guthán cén SIM le húsáid do gach glao. Socraigh SIM réamhshocraithe do ghlaonna ar do ghuthán."),
    "gl": ("Avisos de chamadas", "A chamada pasou ao teléfono",
           "O reloxo non recibiu o audio da chamada a tempo, polo que a chamada quedou no teléfono. Adoita pasar cando o teléfono pregunta que SIM usar en cada chamada. Configura unha SIM predeterminada para as chamadas no teléfono."),
    "gu": ("કૉલ સૂચનાઓ", "કૉલ તમારા ફોન પર ખસેડાયો",
           "ઘડિયાળને કૉલનો ઑડિયો સમયસર મળ્યો નહીં, તેથી કૉલ તમારા ફોન પર જ રહ્યો. સામાન્ય રીતે આવું ત્યારે થાય છે જ્યારે ફોન દરેક કૉલ માટે કયું સિમ વાપરવું તે પૂછે છે. તમારા ફોનમાં કૉલ માટે ડિફૉલ્ટ સિમ સેટ કરો."),
    "ha": ("Sanarwar kira", "An mayar da kiran zuwa wayarka",
           "Agogon bai sami sautin kiran a kan lokaci ba, don haka kiran ya ci gaba a wayarka. Wannan yakan faru idan waya tana tambayar wane SIM za a yi amfani da shi a kowane kira. Saita tsohon SIM don kira a wayarka."),
    "iw": ("התראות שיחה", "השיחה עברה לטלפון",
           "השעון לא קיבל את שמע השיחה בזמן, ולכן השיחה נשארה בטלפון. זה קורה בדרך כלל כשהטלפון שואל באיזה SIM להשתמש בכל שיחה. הגדירו SIM ברירת מחדל לשיחות בטלפון."),
    "hi": ("कॉल सूचनाएं", "कॉल आपके फ़ोन पर चला गया",
           "घड़ी को कॉल का ऑडियो समय पर नहीं मिला, इसलिए कॉल आपके फ़ोन पर ही रहा। आमतौर पर ऐसा तब होता है जब फ़ोन हर कॉल के लिए पूछता है कि कौन-सा सिम इस्तेमाल करना है। अपने फ़ोन में कॉल के लिए एक डिफ़ॉल्ट सिम सेट करें।"),
    "hr": ("Obavijesti o pozivima", "Poziv je prebačen na telefon",
           "Sat nije na vrijeme primio zvuk poziva pa je poziv ostao na vašem telefonu. To se obično događa kada telefon pri svakom pozivu pita koju SIM karticu upotrijebiti. Postavite zadanu SIM karticu za pozive na telefonu."),
    "hu": ("Hívásértesítések", "A hívás átkerült a telefonra",
           "Az óra nem kapta meg időben a hívás hangját, ezért a hívás a telefonon maradt. Ez általában akkor fordul elő, ha a telefon minden hívásnál megkérdezi, melyik SIM-kártyát használja. Állítson be alapértelmezett SIM-kártyát a hívásokhoz a telefonon."),
    "hy": ("Զանգերի ծանուցումներ", "Զանգը փոխանցվեց հեռախոսին",
           "Ժամացույցը ժամանակին չստացավ զանգի ձայնը, ուստի զանգը մնաց ձեր հեռախոսում։ Սովորաբար դա տեղի է ունենում, երբ հեռախոսը յուրաքանչյուր զանգի ժամանակ հարցնում է, թե որ SIM քարտն օգտագործել։ Հեռախոսում զանգերի համար կանխադրված SIM քարտ ընտրեք։"),
    "in": ("Pemberitahuan panggilan", "Panggilan dipindahkan ke ponsel",
           "Jam tidak menerima audio panggilan tepat waktu, jadi panggilan tetap di ponsel Anda. Ini biasanya terjadi jika ponsel menanyakan SIM mana yang akan digunakan untuk setiap panggilan. Tetapkan SIM default untuk panggilan di ponsel Anda."),
    "ig": ("Ọkwa oku", "Ebugara oku ahụ gaa na ekwentị gị",
           "Elekere enwetaghị ụda oku ahụ n'oge, ya mere oku ahụ nọgidere na ekwentị gị. Nke a na-emekarị mgbe ekwentị na-ajụ SIM a ga-eji maka oku ọ bụla. Họrọ SIM ndabara maka oku na ekwentị gị."),
    "is": ("Símtalstilkynningar", "Símtalið færðist í símann",
           "Úrið fékk ekki hljóð símtalsins í tæka tíð og því hélst símtalið í símanum. Þetta gerist yfirleitt þegar síminn spyr hvaða SIM-kort eigi að nota fyrir hvert símtal. Veldu sjálfgefið SIM-kort fyrir símtöl í símanum."),
    "it": ("Avvisi di chiamata", "Chiamata passata al telefono",
           "L'orologio non ha ricevuto in tempo l'audio della chiamata, quindi la chiamata è rimasta sul telefono. Di solito succede quando il telefono chiede quale SIM usare per ogni chiamata. Imposta una SIM predefinita per le chiamate sul telefono."),
    "ja": ("通話のお知らせ", "通話がスマートフォンに切り替わりました",
           "ウォッチが通話の音声を時間内に受信できなかったため、通話はスマートフォンに残りました。通常、スマートフォンが通話のたびに使用するSIMを確認する設定になっていると発生します。スマートフォンで通話用のデフォルトSIMを設定してください。"),
    "ka": ("ზარის შეტყობინებები", "ზარი გადავიდა ტელეფონზე",
           "საათმა ზარის ხმა დროულად ვერ მიიღო, ამიტომ ზარი თქვენს ტელეფონზე დარჩა. ეს ჩვეულებრივ ხდება, როცა ტელეფონი ყოველ ზარზე გეკითხებათ, რომელი SIM გამოიყენოს. დააყენეთ ზარებისთვის ნაგულისხმევი SIM ტელეფონზე."),
    "kk": ("Қоңырау хабарландырулары", "Қоңырау телефонға ауысты",
           "Сағат қоңырау дыбысын уақытында алмады, сондықтан қоңырау телефоныңызда қалды. Бұл әдетте телефон әр қоңырау сайын қай SIM картаны пайдалану керектігін сұрағанда болады. Телефоныңызда қоңыраулар үшін әдепкі SIM картаны орнатыңыз."),
    "km": ("ការជូនដំណឹងអំពីការហៅ", "ការហៅត្រូវបានផ្ទេរទៅទូរសព្ទរបស់អ្នក",
           "នាឡិកាមិនបានទទួលសំឡេងការហៅទាន់ពេលទេ ដូច្នេះការហៅនៅតែស្ថិតនៅលើទូរសព្ទរបស់អ្នក។ ជាធម្មតា វាកើតឡើងនៅពេលទូរសព្ទសួរថាត្រូវប្រើស៊ីមណាសម្រាប់ការហៅនីមួយៗ។ កំណត់ស៊ីមលំនាំដើមសម្រាប់ការហៅនៅលើទូរសព្ទរបស់អ្នក។"),
    "kn": ("ಕರೆ ಸೂಚನೆಗಳು", "ಕರೆಯನ್ನು ನಿಮ್ಮ ಫೋನ್‌ಗೆ ವರ್ಗಾಯಿಸಲಾಗಿದೆ",
           "ವಾಚ್‌ಗೆ ಕರೆಯ ಆಡಿಯೊ ಸಮಯಕ್ಕೆ ಸರಿಯಾಗಿ ಸಿಗಲಿಲ್ಲ, ಆದ್ದರಿಂದ ಕರೆ ನಿಮ್ಮ ಫೋನ್‌ನಲ್ಲೇ ಉಳಿಯಿತು. ಪ್ರತಿ ಕರೆಗೆ ಯಾವ ಸಿಮ್ ಬಳಸಬೇಕು ಎಂದು ಫೋನ್ ಕೇಳಿದಾಗ ಸಾಮಾನ್ಯವಾಗಿ ಹೀಗಾಗುತ್ತದೆ. ನಿಮ್ಮ ಫೋನ್‌ನಲ್ಲಿ ಕರೆಗಳಿಗೆ ಡೀಫಾಲ್ಟ್ ಸಿಮ್ ಹೊಂದಿಸಿ."),
    "ko": ("통화 알림", "통화가 휴대전화로 전환됨",
           "워치가 통화 오디오를 제때 받지 못해 통화가 휴대전화에서 계속되었습니다. 보통 휴대전화가 통화할 때마다 사용할 SIM을 묻는 경우에 발생합니다. 휴대전화에서 통화용 기본 SIM을 설정하세요."),
    "ky": ("Чалуу билдирмелери", "Чалуу телефонго өттү",
           "Саат чалуунун үнүн убагында алган жок, ошондуктан чалуу телефонуңузда калды. Адатта бул телефон ар бир чалууда кайсы SIM картаны колдонууну сураганда болот. Телефонуңузда чалуулар үчүн демейки SIM картаны коюңуз."),
    "lo": ("ການແຈ້ງເຕືອນການໂທ", "ການໂທຖືກຍ້າຍໄປໂທລະສັບຂອງທ່ານ",
           "ໂມງບໍ່ໄດ້ຮັບສຽງການໂທທັນເວລາ, ສະນັ້ນການໂທຈຶ່ງຍັງຢູ່ໃນໂທລະສັບຂອງທ່ານ. ໂດຍປົກກະຕິ ສິ່ງນີ້ເກີດຂຶ້ນເມື່ອໂທລະສັບຖາມວ່າຈະໃຊ້ຊິມໃດສຳລັບການໂທແຕ່ລະຄັ້ງ. ຕັ້ງຊິມເລີ່ມຕົ້ນສຳລັບການໂທໃນໂທລະສັບຂອງທ່ານ."),
    "lt": ("Skambučių pranešimai", "Skambutis perkeltas į telefoną",
           "Laikrodis laiku negavo skambučio garso, todėl skambutis liko telefone. Paprastai taip nutinka, kai telefonas kiekvieno skambučio metu klausia, kurią SIM kortelę naudoti. Telefone nustatykite numatytąją SIM kortelę skambučiams."),
    "lv": ("Zvanu paziņojumi", "Zvans tika pārvietots uz tālruni",
           "Pulkstenis laikus nesaņēma zvana skaņu, tāpēc zvans palika jūsu tālrunī. Parasti tā notiek, ja tālrunis katram zvanam jautā, kuru SIM karti izmantot. Iestatiet tālrunī noklusējuma SIM karti zvaniem."),
    "mk": ("Известувања за повици", "Повикот се префрли на телефонот",
           "Часовникот не го доби звукот од повикот навреме, па повикот остана на вашиот телефон. Ова обично се случува кога телефонот за секој повик прашува која SIM-картичка да ја користи. Поставете стандардна SIM-картичка за повици на телефонот."),
    "ml": ("കോൾ അറിയിപ്പുകൾ", "കോൾ നിങ്ങളുടെ ഫോണിലേക്ക് മാറി",
           "കോളിന്റെ ഓഡിയോ വാച്ചിന് സമയത്ത് ലഭിച്ചില്ല, അതിനാൽ കോൾ നിങ്ങളുടെ ഫോണിൽ തന്നെ തുടർന്നു. ഓരോ കോളിനും ഏത് സിം ഉപയോഗിക്കണമെന്ന് ഫോൺ ചോദിക്കുമ്പോഴാണ് സാധാരണ ഇങ്ങനെ സംഭവിക്കുന്നത്. നിങ്ങളുടെ ഫോണിൽ കോളുകൾക്കായി ഒരു ഡിഫോൾട്ട് സിം സജ്ജീകരിക്കുക."),
    "mn": ("Дуудлагын мэдэгдэл", "Дуудлага утас руу шилжлээ",
           "Цаг дуудлагын дууг цагтаа хүлээж аваагүй тул дуудлага таны утсанд үлдлээ. Энэ нь ихэвчлэн утас дуудлага бүрт аль SIM-ийг ашиглахыг асуудаг үед тохиолддог. Утсандаа дуудлагад зориулсан үндсэн SIM-ийг тохируулна уу."),
    "mr": ("कॉल सूचना", "कॉल तुमच्या फोनवर गेला",
           "घड्याळाला कॉलचा ऑडिओ वेळेत मिळाला नाही, त्यामुळे कॉल तुमच्या फोनवरच राहिला. फोन प्रत्येक कॉलसाठी कोणते सिम वापरायचे ते विचारतो तेव्हा सहसा असे होते. तुमच्या फोनवर कॉलसाठी डीफॉल्ट सिम सेट करा."),
    "ms": ("Pemberitahuan panggilan", "Panggilan dipindahkan ke telefon",
           "Jam tangan tidak menerima audio panggilan tepat pada masanya, jadi panggilan kekal pada telefon anda. Ini biasanya berlaku apabila telefon bertanya SIM mana yang hendak digunakan untuk setiap panggilan. Tetapkan SIM lalai untuk panggilan pada telefon anda."),
    "my": ("ခေါ်ဆိုမှု အသိပေးချက်များ", "ခေါ်ဆိုမှုကို သင့်ဖုန်းသို့ ပြောင်းလိုက်သည်",
           "နာရီသည် ခေါ်ဆိုမှုအသံကို အချိန်မီ မရရှိသဖြင့် ခေါ်ဆိုမှုသည် သင့်ဖုန်းပေါ်တွင် ကျန်ရှိနေသည်။ ဖုန်းက ခေါ်ဆိုမှုတိုင်းအတွက် မည်သည့် SIM ကို သုံးမည်ကို မေးသည့်အခါ ယေဘုယျအားဖြင့် ဖြစ်တတ်သည်။ သင့်ဖုန်းတွင် ခေါ်ဆိုမှုများအတွက် မူရင်း SIM ကို သတ်မှတ်ပါ။"),
    "nb": ("Anropsvarsler", "Anropet ble flyttet til telefonen",
           "Klokken fikk ikke lyden fra anropet i tide, så anropet ble værende på telefonen. Dette skjer vanligvis når telefonen spør hvilket SIM-kort som skal brukes for hvert anrop. Velg et standard SIM-kort for anrop på telefonen."),
    "ne": ("कल सूचनाहरू", "कल तपाईंको फोनमा सारियो",
           "घडीले समयमै कलको अडियो पाएन, त्यसैले कल तपाईंको फोनमै रह्यो। फोनले हरेक कलमा कुन SIM प्रयोग गर्ने भनेर सोध्दा प्रायः यस्तो हुन्छ। आफ्नो फोनमा कलका लागि पूर्वनिर्धारित SIM सेट गर्नुहोस्।"),
    "nl": ("Oproepmeldingen", "Gesprek verplaatst naar je telefoon",
           "Het horloge kreeg het gespreksgeluid niet op tijd binnen, dus het gesprek bleef op je telefoon. Dit gebeurt meestal als de telefoon bij elk gesprek vraagt welke simkaart moet worden gebruikt. Stel op je telefoon een standaardsimkaart voor gesprekken in."),
    "or": ("କଲ୍ ବିଜ୍ଞପ୍ତି", "କଲ୍ ଆପଣଙ୍କ ଫୋନକୁ ଚାଲିଗଲା",
           "ୱାଚ୍ ସମୟରେ କଲ୍‌ର ଅଡିଓ ପାଇଲା ନାହିଁ, ତେଣୁ କଲ୍ ଆପଣଙ୍କ ଫୋନରେ ରହିଗଲା। ଫୋନ୍ ପ୍ରତ୍ୟେକ କଲ୍ ପାଇଁ କେଉଁ SIM ବ୍ୟବହାର କରିବ ବୋଲି ପଚାରିଲେ ସାଧାରଣତଃ ଏହା ହୁଏ। ଆପଣଙ୍କ ଫୋନରେ କଲ୍ ପାଇଁ ଏକ ଡିଫଲ୍ଟ SIM ସେଟ୍ କରନ୍ତୁ।"),
    "pa": ("ਕਾਲ ਸੂਚਨਾਵਾਂ", "ਕਾਲ ਤੁਹਾਡੇ ਫ਼ੋਨ 'ਤੇ ਚਲੀ ਗਈ",
           "ਘੜੀ ਨੂੰ ਕਾਲ ਦੀ ਆਡੀਓ ਸਮੇਂ ਸਿਰ ਨਹੀਂ ਮਿਲੀ, ਇਸ ਲਈ ਕਾਲ ਤੁਹਾਡੇ ਫ਼ੋਨ 'ਤੇ ਹੀ ਰਹੀ। ਆਮ ਤੌਰ 'ਤੇ ਅਜਿਹਾ ਉਦੋਂ ਹੁੰਦਾ ਹੈ ਜਦੋਂ ਫ਼ੋਨ ਹਰ ਕਾਲ ਲਈ ਪੁੱਛਦਾ ਹੈ ਕਿ ਕਿਹੜਾ ਸਿਮ ਵਰਤਣਾ ਹੈ। ਆਪਣੇ ਫ਼ੋਨ ਵਿੱਚ ਕਾਲਾਂ ਲਈ ਇੱਕ ਪੂਰਵ-ਨਿਰਧਾਰਤ ਸਿਮ ਸੈੱਟ ਕਰੋ।"),
    "pl": ("Powiadomienia o połączeniach", "Połączenie przeniesiono na telefon",
           "Zegarek nie otrzymał na czas dźwięku połączenia, więc połączenie zostało na telefonie. Zwykle dzieje się tak, gdy telefon przy każdym połączeniu pyta, której karty SIM użyć. Ustaw na telefonie domyślną kartę SIM do połączeń."),
    "pt": ("Avisos de chamadas", "A chamada passou para o smartphone",
           "O relógio não recebeu o áudio da chamada a tempo, então a chamada ficou no smartphone. Isso costuma acontecer quando o smartphone pergunta qual chip usar em cada chamada. Defina um chip padrão para chamadas no smartphone."),
    "pt-rPT": ("Avisos de chamadas", "A chamada passou para o telemóvel",
               "O relógio não recebeu o áudio da chamada a tempo, por isso a chamada ficou no telemóvel. Isto acontece normalmente quando o telemóvel pergunta que SIM usar em cada chamada. Defina um SIM predefinido para chamadas no telemóvel."),
    "ro": ("Notificări de apel", "Apelul a trecut pe telefon",
           "Ceasul nu a primit la timp sunetul apelului, așa că apelul a rămas pe telefon. De obicei, se întâmplă când telefonul întreabă ce SIM să folosească la fiecare apel. Setați un SIM implicit pentru apeluri pe telefon."),
    "ru": ("Уведомления о вызовах", "Вызов переведён на телефон",
           "Часы не получили звук вызова вовремя, поэтому вызов остался на телефоне. Обычно это происходит, когда телефон при каждом вызове спрашивает, какую SIM-карту использовать. Выберите на телефоне SIM-карту по умолчанию для вызовов."),
    "si": ("ඇමතුම් දැනුම්දීම්", "ඇමතුම ඔබගේ දුරකථනයට මාරු විය",
           "ඔරලෝසුවට ඇමතුමේ ශ්‍රව්‍යය නියමිත වේලාවට නොලැබුණු නිසා ඇමතුම ඔබගේ දුරකථනයේම පැවතුණි. දුරකථනය සෑම ඇමතුමකටම භාවිත කළ යුතු SIM පත කුමක්දැයි අසන විට සාමාන්‍යයෙන් මෙය සිදු වේ. ඔබගේ දුරකථනයේ ඇමතුම් සඳහා පෙරනිමි SIM පතක් සකසන්න."),
    "sk": ("Upozornenia na hovory", "Hovor sa presunul do telefónu",
           "Hodinky nedostali zvuk hovoru včas, takže hovor zostal v telefóne. Zvyčajne sa to stane, keď sa telefón pri každom hovore pýta, ktorú SIM kartu použiť. Nastavte v telefóne predvolenú SIM kartu na hovory."),
    "sl": ("Obvestila o klicih", "Klic je bil preusmerjen na telefon",
           "Ura ni pravočasno prejela zvoka klica, zato je klic ostal v telefonu. To se običajno zgodi, ko telefon pri vsakem klicu vpraša, katero kartico SIM naj uporabi. V telefonu nastavite privzeto kartico SIM za klice."),
    "sq": ("Njoftime për telefonatat", "Telefonata kaloi te telefoni",
           "Ora nuk e mori audion e telefonatës në kohë, prandaj telefonata mbeti në telefonin tënd. Kjo ndodh zakonisht kur telefoni pyet se cilën kartë SIM të përdorë për çdo telefonatë. Cakto një kartë SIM të parazgjedhur për telefonatat në telefon."),
    "sr": ("Обавештења о позивима", "Позив је пребачен на телефон",
           "Сат није на време добио звук позива, па је позив остао на телефону. То се обично дешава када телефон за сваки позив пита коју SIM картицу да користи. Подесите подразумевану SIM картицу за позиве на телефону."),
    "b+sr+Latn": ("Obaveštenja o pozivima", "Poziv je prebačen na telefon",
                  "Sat nije na vreme dobio zvuk poziva, pa je poziv ostao na telefonu. To se obično dešava kada telefon za svaki poziv pita koju SIM karticu da koristi. Podesite podrazumevanu SIM karticu za pozive na telefonu."),
    "sv": ("Samtalsaviseringar", "Samtalet flyttades till telefonen",
           "Klockan fick inte samtalsljudet i tid, så samtalet stannade kvar i telefonen. Det händer oftast när telefonen frågar vilket SIM-kort som ska användas för varje samtal. Ställ in ett standard-SIM för samtal i telefonen."),
    "ta": ("அழைப்பு அறிவிப்புகள்", "அழைப்பு உங்கள் மொபைலுக்கு மாற்றப்பட்டது",
           "அழைப்பின் ஆடியோ வாட்ச்சுக்குச் சரியான நேரத்தில் கிடைக்கவில்லை, எனவே அழைப்பு உங்கள் மொபைலிலேயே தொடர்ந்தது. ஒவ்வொரு அழைப்புக்கும் எந்த சிம்மைப் பயன்படுத்த வேண்டும் என மொபைல் கேட்கும்போது பொதுவாக இது நடக்கும். உங்கள் மொபைலில் அழைப்புகளுக்கான இயல்புநிலை சிம்மை அமைக்கவும்."),
    "te": ("కాల్ నోటిఫికేషన్‌లు", "కాల్ మీ ఫోన్‌కు మారింది",
           "వాచ్‌కు కాల్ ఆడియో సమయానికి అందలేదు, కాబట్టి కాల్ మీ ఫోన్‌లోనే కొనసాగింది. ప్రతి కాల్‌కు ఏ సిమ్ ఉపయోగించాలో ఫోన్ అడిగినప్పుడు సాధారణంగా ఇలా జరుగుతుంది. మీ ఫోన్‌లో కాల్‌ల కోసం డిఫాల్ట్ సిమ్‌ను సెట్ చేయండి."),
    "tg": ("Огоҳиҳои занг", "Занг ба телефон гузашт",
           "Соат садои зангро сари вақт нагирифт, бинобар ин занг дар телефони шумо монд. Ин одатан вақте рух медиҳад, ки телефон ҳар дафъа мепурсад, ки кадом SIM-кортро истифода барад. Дар телефон барои зангҳо SIM-корти пешфарзро муқаррар кунед."),
    "th": ("การแจ้งเตือนการโทร", "ย้ายสายไปที่โทรศัพท์แล้ว",
           "นาฬิกาได้รับเสียงของสายไม่ทันเวลา สายจึงยังอยู่ที่โทรศัพท์ของคุณ ปกติจะเกิดขึ้นเมื่อโทรศัพท์ถามว่าจะใช้ซิมใดทุกครั้งที่โทร ตั้งค่าซิมเริ่มต้นสำหรับการโทรในโทรศัพท์ของคุณ"),
    "tk": ("Jaň habarnamalary", "Jaň telefonyňyza geçdi",
           "Sagat jaňyň sesini wagtynda almady, şonuň üçin jaň telefonyňyzda galdy. Bu, adatça, telefon her jaňda haýsy SIM-kartyň ulanyljakdygyny soranda bolýar. Telefonyňyzda jaňlar üçin deslapky SIM-karty saýlaň."),
    "tl": ("Mga abiso sa tawag", "Nalipat ang tawag sa iyong telepono",
           "Hindi natanggap ng relo ang audio ng tawag sa tamang oras, kaya nanatili ang tawag sa iyong telepono. Karaniwan itong nangyayari kapag tinatanong ng telepono kung aling SIM ang gagamitin sa bawat tawag. Magtakda ng default na SIM para sa mga tawag sa iyong telepono."),
    "tr": ("Arama bildirimleri", "Arama telefonunuza aktarıldı",
           "Saat arama sesini zamanında alamadı, bu yüzden arama telefonunuzda kaldı. Bu genellikle telefon her aramada hangi SIM'in kullanılacağını sorduğunda olur. Telefonunuzda aramalar için varsayılan bir SIM belirleyin."),
    "ug": ("چاقىرىش ئۇقتۇرۇشلىرى", "چاقىرىش تېلېفونىڭىزغا يۆتكەلدى",
           "سائەت چاقىرىشنىڭ ئاۋازىنى ۋاقتىدا تاپشۇرۇۋالمىدى، شۇڭا چاقىرىش تېلېفونىڭىزدا قالدى. بۇ ئادەتتە تېلېفون ھەر قېتىملىق چاقىرىشتا قايسى SIM كارتىنى ئىشلىتىشنى سورىغاندا يۈز بېرىدۇ. تېلېفونىڭىزدا چاقىرىش ئۈچۈن كۆڭۈلدىكى SIM كارتىنى بەلگىلەڭ."),
    "uk": ("Сповіщення про виклики", "Виклик перейшов на телефон",
           "Годинник не отримав звук виклику вчасно, тому виклик залишився на телефоні. Зазвичай це стається, коли телефон під час кожного виклику питає, яку SIM-карту використовувати. Виберіть на телефоні SIM-карту за замовчуванням для викликів."),
    "ur": ("کال کی اطلاعات", "کال آپ کے فون پر منتقل ہو گئی",
           "گھڑی کو کال کی آڈیو بروقت نہیں ملی، اس لیے کال آپ کے فون پر ہی رہی۔ عام طور پر ایسا تب ہوتا ہے جب فون ہر کال کے لیے پوچھتا ہے کہ کون سا SIM استعمال کرنا ہے۔ اپنے فون میں کالز کے لیے ڈیفالٹ SIM سیٹ کریں۔"),
    "uz": ("Chaqiruv bildirishnomalari", "Chaqiruv telefonga o‘tdi",
           "Soat chaqiruv ovozini o‘z vaqtida olmadi, shuning uchun chaqiruv telefoningizda qoldi. Odatda bu telefon har bir chaqiruvda qaysi SIM kartadan foydalanishni so‘raganda yuz beradi. Telefoningizda chaqiruvlar uchun standart SIM kartani belgilang."),
    "vi": ("Thông báo cuộc gọi", "Cuộc gọi đã chuyển sang điện thoại",
           "Đồng hồ không nhận được âm thanh cuộc gọi kịp thời nên cuộc gọi vẫn ở trên điện thoại của bạn. Điều này thường xảy ra khi điện thoại hỏi dùng SIM nào cho mỗi cuộc gọi. Hãy đặt SIM mặc định cho cuộc gọi trên điện thoại."),
    "yo": ("Àwọn ìfitónilétí ìpè", "A gbé ìpè náà lọ sí fóònù rẹ",
           "Aago kò gba ohùn ìpè náà ní àkókò, nítorí náà ìpè náà dúró sórí fóònù rẹ. Èyí sábà máa ń ṣẹlẹ̀ nígbà tí fóònù bá ń béèrè SIM tí yóò lò fún ìpè kọ̀ọ̀kan. Ṣètò SIM àkọ́kọ́ fún àwọn ìpè lórí fóònù rẹ."),
    "zh-rCN": ("通话通知", "通话已转到手机",
               "手表未能及时收到通话音频，因此通话留在了手机上。这通常发生在手机每次通话都询问使用哪张 SIM 卡时。请在手机上设置默认的通话 SIM 卡。"),
    "zh-rTW": ("通話通知", "通話已轉到手機",
               "手錶未能及時收到通話音訊，因此通話留在手機上。這通常發生在手機每次通話都會詢問要使用哪張 SIM 卡時。請在手機上設定預設的通話 SIM 卡。"),
    "zh-rHK": ("通話通知", "通話已轉到手機",
               "手錶未能及時接收通話音訊，因此通話留在手機上。這通常在手機每次通話都詢問使用哪張 SIM 卡時發生。請在手機上設定預設的通話 SIM 卡。"),
    "zu": ("Izaziso zekholi", "Ikholi idluliselwe efonini yakho",
           "Iwashi alilitholanga umsindo wekholi ngesikhathi, ngakho ikholi ihlale efonini yakho. Lokhu kuvame ukwenzeka lapho ifoni ibuza ukuthi iyiphi i-SIM okufanele isetshenziswe kukholi ngayinye. Setha i-SIM ezenzakalelayo yamakholi efonini yakho."),
}
# Label of the phone alarm in the Samsung Alarm app (watch only, alarm_label.xml).
# It is part of Samsung's deduplication key (time + name + repeat): do not change it
# lightly or old rows end up duplicated.
L = {
    "": "Phone", "es": "Teléfono", "es-rAR": "Teléfono", "af": "Foon", "am": "ስልክ", "ar": "الهاتف",
    "as": "ফোন", "az": "Telefon", "be": "Тэлефон", "bg": "Телефон", "bn": "ফোন", "bo": "ཁ་པར།",
    "bs": "Telefon", "ca": "Telèfon", "cs": "Telefon", "da": "Telefon", "de": "Telefon",
    "el": "Τηλέφωνο", "et": "Telefon", "eu": "Telefonoa", "fa": "تلفن", "fi": "Puhelin",
    "fr": "Téléphone", "ga": "Fón", "gl": "Teléfono", "gu": "ફોન", "ha": "Waya", "iw": "טלפון",
    "hi": "फ़ोन", "hr": "Telefon", "hu": "Telefon", "hy": "Հեռախոս", "in": "Ponsel", "ig": "Ekwentị",
    "is": "Sími", "it": "Telefono", "ja": "スマートフォン", "ka": "ტელეფონი", "kk": "Телефон",
    "km": "ទូរសព្ទ", "kn": "ಫೋನ್", "ko": "휴대전화", "ky": "Телефон", "lo": "ໂທລະສັບ",
    "lt": "Telefonas", "lv": "Tālrunis", "mk": "Телефон", "ml": "ഫോൺ", "mn": "Утас", "mr": "फोन",
    "ms": "Telefon", "my": "ဖုန်း", "nb": "Telefon", "ne": "फोन", "nl": "Telefoon", "or": "ଫୋନ୍",
    "pa": "ਫ਼ੋਨ", "pl": "Telefon", "pt": "Smartphone", "pt-rPT": "Telemóvel", "ro": "Telefon",
    "ru": "Телефон", "si": "දුරකථනය", "sk": "Telefón", "sl": "Telefon", "sq": "Telefoni",
    "sr": "Телефон", "b+sr+Latn": "Telefon", "sv": "Telefon", "ta": "ஃபோன்", "te": "ఫోన్",
    "tg": "Телефон", "th": "โทรศัพท์", "tk": "Telefon", "tl": "Telepono", "tr": "Telefon",
    "ug": "تېلېفون", "uk": "Телефон", "ur": "فون", "uz": "Telefon", "vi": "Điện thoại",
    "yo": "Fóònù", "zh-rCN": "手机", "zh-rTW": "手機", "zh-rHK": "手機", "zu": "Ifoni",
}
assert set(L) == set(S)

# New language codes in addition to the legacy ones (Android accepts both depending on version).
ALIAS = {"he": "iw", "id": "in"}

KEYS = ("call_notice_channel", "call_pulled_title", "call_pulled_text")


def esc(s):
    s = s.replace("\\", "\\\\").replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
    s = s.replace("'", "\\'").replace('"', '\\"')
    if s[:1] in ("@", "?"):
        s = "\\" + s
    return s


def write(app, qual, vals, keys=KEYS, name="call_notice.xml"):
    d = os.path.join(ROOT, app, "res", "values" + ("-" + qual if qual else ""))
    os.makedirs(d, exist_ok=True)
    body = "".join(f'    <string name="{k}">{esc(v)}</string>\n' for k, v in zip(keys, vals))
    with open(os.path.join(d, name), "w", encoding="utf-8") as f:
        f.write('<?xml version="1.0" encoding="utf-8"?>\n'
                '<!-- Generated by shm-bridge/scripts/gen-call-notice-strings.py: do not edit by hand. -->\n'
                f"<resources>\n{body}</resources>\n")


def main():
    n = 0
    for app in ("watch", "phone"):
        for q, v in S.items():
            write(app, q, v)
            n += 1
        for new, old in ALIAS.items():
            write(app, new, S[old])
            n += 1
    for q in list(S) + list(ALIAS):
        write("watch", q, (L[ALIAS.get(q, q)],), ("alarm_phone_label",), "alarm_label.xml")
        n += 1
    print(f"{n} files ({len(S) + len(ALIAS)} locales x 2 apps)")


if __name__ == "__main__":
    main()
