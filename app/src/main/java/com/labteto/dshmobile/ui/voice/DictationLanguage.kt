package com.labteto.dshmobile.ui.voice

import java.util.Locale

/**
 * Which language the recogniser is asked to listen for.
 *
 * What is stored is a plain string, so it survives a settings migration the way every other choice
 * in `AppSettings` does:
 *  - `null`: follow the app's own language (the per-app language setting), the default;
 *  - [DEVICE]: do not name a language, so the recogniser uses the phone's own;
 *  - one of [DictationLanguages]: that language, whatever the app is shown in.
 */
object DictationLanguageChoice {
    /** The stored value for "let the phone decide". */
    const val DEVICE = "device"
}

/**
 * The app's eleven languages and the recogniser tag each is asked for.
 *
 * The recogniser wants a BCP 47 tag, and a bare language is ambiguous for some services, so each
 * entry carries the region that has the most speakers of it. A service that has no model for the
 * region still has the chance to answer with a close one; one that has none at all reports a
 * language error and the controller asks again without a language (see [languageAttempts]).
 */
val DictationLanguages: Map<String, String> = linkedMapOf(
    "en" to "en-US",
    "zh" to "zh-CN",
    "hi" to "hi-IN",
    "es" to "es-ES",
    "fr" to "fr-FR",
    "ar" to "ar-SA",
    "bn" to "bn-BD",
    "pt" to "pt-BR",
    "ru" to "ru-RU",
    "ur" to "ur-PK",
    "th" to "th-TH",
)

/**
 * The tag to hand the recogniser for a stored [choice], or null to leave the language unnamed.
 *
 * @param choice what is stored: null, [DictationLanguageChoice.DEVICE] or an app language tag.
 * @param appLanguage the per-app language override (`AppSettings.localeOverride`), null for the system's.
 */
fun resolveDictationLanguage(choice: String?, appLanguage: String?): String? = when (choice) {
    null -> appLanguage?.let(::recognizerTagFor)
    DictationLanguageChoice.DEVICE -> null
    else -> recognizerTagFor(choice)
}

/**
 * The tags to try in order for one dictation: the language asked for, then the phone's own.
 *
 * A recogniser without a model for the first answers with a language error, and a person who set
 * the app to a language their speech service lacks would otherwise get an error where the phone's
 * default language would have worked. Always ends with null, so there is always a last attempt that
 * names nothing; a take that already asks for nothing has just that one.
 */
fun languageAttempts(choice: String?, appLanguage: String?): List<String?> {
    val wanted = resolveDictationLanguage(choice, appLanguage)
    return if (wanted == null) listOf(null) else listOf(wanted, null)
}

/**
 * The recogniser tag for an app language code such as `zh`, `pt` or `th`, tolerating a region or
 * a script on it (`zh-CN`, `pt_BR`, `zh-Hans`). Anything outside the app's eleven languages is null.
 */
internal fun recognizerTagFor(appTag: String): String? {
    val primary = appTag.trim().lowercase(Locale.ROOT).split('-', '_').firstOrNull().orEmpty()
    return DictationLanguages[primary]
}

/** Whether [choice] is something the picker offers, so a stale stored value reads as the default. */
fun isKnownDictationChoice(choice: String?): Boolean =
    choice == null || choice == DictationLanguageChoice.DEVICE || choice in DictationLanguages
