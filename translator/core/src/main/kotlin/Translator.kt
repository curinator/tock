/*
 * Copyright (C) 2017 VSCT
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package fr.vsct.tock.translator

import com.google.common.cache.Cache
import com.google.common.cache.CacheBuilder
import fr.vsct.tock.shared.Executor
import fr.vsct.tock.shared.booleanProperty
import fr.vsct.tock.shared.defaultLocale
import fr.vsct.tock.shared.defaultNamespace
import fr.vsct.tock.shared.error
import fr.vsct.tock.shared.injector
import fr.vsct.tock.shared.longProperty
import fr.vsct.tock.shared.provide
import fr.vsct.tock.translator.UserInterfaceType.textAndVoiceAssistant
import fr.vsct.tock.translator.UserInterfaceType.textChat
import fr.vsct.tock.translator.UserInterfaceType.voiceAssistant
import mu.KotlinLogging
import org.litote.kmongo.toId
import java.text.ChoiceFormat
import java.text.MessageFormat
import java.time.Duration
import java.util.Formatter
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.LongAdder

/**
 * The main entry class of translator module.
 */
object Translator {

    private val logger = KotlinLogging.logger {}

    private val loadAllLabelsOfDefaultNamespace = booleanProperty("tock_load_all_labels_of_default_namespace", true)

    /**
     * Translator and i18n support is disable by default.
     * Set it to true if you want to enable i18n.
     */
    @Volatile
    var enabled: Boolean = booleanProperty("tock_i18n_enabled", false)

    private val statWriteEnabled = booleanProperty("tock_i18n_stat_write_enabled", true)

    private val defaultInterface: UserInterfaceType = textChat

    private val i18nDAO: I18nDAO get() = injector.provide()
    private val translator: TranslatorEngine get() = injector.provide()

    private val cache: MutableMap<String, I18nLabel> = ConcurrentHashMap()

    private val statsCache: Cache<I18nLabelStatKey, LongAdder> by lazy {
        val executor: Executor = injector.provide()
        executor.setPeriodic(Duration.ofMillis(longProperty("tock_i18n_stat_refresh_in_ms", 10000))) {
            if (statsCache.size() != 0L) {
                logger.trace { "persist i18n stats" }
                val stats = HashMap(statsCache.asMap())
                statsCache.invalidateAll()
                stats.forEach { i18nDAO.incrementLabelStat(I18nLabelStat(it.key, it.value.toInt())) }
            }
        }
        CacheBuilder.newBuilder().build<I18nLabelStatKey, LongAdder>()
    }

    private val voiceTransformers: MutableList<VoiceTransformer> = CopyOnWriteArrayList()

    fun initTranslator() {
        if (enabled && loadAllLabelsOfDefaultNamespace) {
            try {
                val labels = i18nDAO.getLabels(defaultNamespace)

                cache.putAll(labels.associateBy { it._id.toString() })
                //clean up cache
                i18nDAO.listenI18n {
                    logger.debug { "remove i18n $it from cache" }
                    cache.remove(it.toString())
                }
            } catch (e: Exception) {
                logger.error(e)
            }
        }
    }

    fun registerVoiceTransformer(transformer: VoiceTransformer) {
        voiceTransformers.add(transformer)
    }

    fun unregisterVoiceTransformer(transformer: VoiceTransformer) {
        voiceTransformers.remove(transformer)
    }

    private fun transformArg(text: Any, locale: Locale, userInterfaceType: UserInterfaceType): Any {
        var t = text
        voiceTransformers.forEach { t = it.transformArg(t, locale, userInterfaceType) }
        return t
    }

    private fun loadLabel(id: String): I18nLabel? {
        return cache[id]?.copy() ?: i18nDAO.getLabelById(id.toId())
    }

    private fun getLabel(id: String): I18nLabel? = loadLabel(id)

    private fun getLabel(key: I18nLabelValue): I18nLabel? = getLabel(key.key)
        ?.apply {
            if (defaultLabel != null && key.defaultLabel != key.defaultLabel.toString()) {
                logger.warn { "default label has changed - old value $defaultLabel - new value : ${key.defaultLabel}" }
            }
        }

    private fun incrementStat(value: I18nLabelValue, context: I18nContext) {
        statsCache.get(I18nLabelStatKey(value, context)) { LongAdder() }.increment()
    }

    fun saveIfNotExist(value: I18nLabelValue): I18nLabel = saveIfNotExist(value, defaultLocale)

    private fun saveIfNotExist(value: I18nLabelValue, defaultLocale: Locale): I18nLabel = getLabel(value) ?: {
        val defaultLabelKey = value.defaultLabel.toString()
        val defaultLabel = I18nLocalizedLabel(defaultLocale, defaultInterface, defaultLabelKey)
        val label =
            I18nLabel(
                value.key.toId(),
                value.namespace,
                value.category,
                LinkedHashSet(listOf(defaultLabel)),
                defaultLabelKey
            )
        i18nDAO.save(label)
        label
    }.invoke()

    /**
     * Creates a new label. If the label key exists, increment the key with a _$count suffix.
     */
    fun create(value: I18nLabelValue, defaultLocale: Locale, alternatives: List<String> = emptyList()): I18nLabel {
        var count = 1;
        var v = value
        while (getLabel(v) != null) {
            v = I18nLabelValue(value.key + "_" + count++, value.namespace, value.category, value.defaultLabel, value.args)
        }
        val defaultLabelKey = v.defaultLabel.toString()
        val defaultLabel = I18nLocalizedLabel(defaultLocale, defaultInterface, defaultLabelKey, alternatives)
        val label =
            I18nLabel(
                v.key.toId(),
                v.namespace,
                v.category,
                LinkedHashSet(listOf(defaultLabel)),
                defaultLabelKey
            )
        i18nDAO.save(label)
        return label
    }

    /**
     * Translates an [I18nLabelValue] for the given [I18nContext].
     */
    fun translate(value: I18nLabelValue, context: I18nContext): TranslatedString {

        if (!enabled) {
            return TranslatedString(
                formatMessage(
                    value.defaultLabel.toString(),
                    context,
                    value.args
                )
            )
        }
        if (value.defaultLabel is TranslatedString) {
            logger.warn { "already translated string is proposed to translation - skipped: $value" }
            return value.defaultLabel
        }
        if (value.defaultLabel is RawString) {
            logger.warn { "raw string is proposed to translation - skipped: $value" }
            return TranslatedString(value.defaultLabel)
        }

        val storedLabel = getLabel(value)
        val (locale, userInterfaceType, connectorId) = context

        val targetDefaultUserInterface = if (userInterfaceType == textAndVoiceAssistant) textChat else userInterfaceType

        if (statWriteEnabled) {
            incrementStat(value, context.copy(userInterfaceType = targetDefaultUserInterface))
        }

        val label = if (storedLabel != null) {
            getLabel(storedLabel, value.defaultLabel.toString(), context)
        } else {
            val defaultLabel = I18nLocalizedLabel(defaultLocale, defaultInterface, value.defaultLabel.toString())
            if (locale != defaultLocale) {
                val localizedLabel = I18nLocalizedLabel(
                    locale,
                    targetDefaultUserInterface,
                    translate(
                        value.defaultLabel.toString(), defaultLocale, locale
                    )
                )
                val label = I18nLabel(
                    value.key.toId(),
                    value.namespace,
                    value.category,
                    LinkedHashSet(listOf(defaultLabel, localizedLabel)),
                    value.defaultLabel.toString()
                )
                i18nDAO.save(label)
                localizedLabel.label
            } else {
                val interfaceLabel =
                    if (defaultInterface != targetDefaultUserInterface)
                        I18nLocalizedLabel(locale, targetDefaultUserInterface, defaultLabel.label)
                    else null
                val label = I18nLabel(
                    value.key.toId(),
                    value.namespace,
                    value.category,
                    LinkedHashSet(listOfNotNull(defaultLabel, interfaceLabel)),
                    value.defaultLabel.toString()
                )
                i18nDAO.save(label)
                value.defaultLabel
            }
        }

        logger.debug { "find label $label for $value, $locale, $userInterfaceType and $connectorId" }

        return if (label is TextAndVoiceTranslatedString) {
            label.copy(
                text = formatMessage(label.text.toString(), context.copy(userInterfaceType = textChat), value.args),
                voice = formatMessage(
                    label.voice.toString(),
                    context.copy(userInterfaceType = voiceAssistant),
                    value.args
                )
            )
        } else {
            TranslatedString(
                formatMessage(
                    label.toString(),
                    context.copy(userInterfaceType = targetDefaultUserInterface),
                    value.args
                )
            )
        }
    }

    private fun getLabel(
        i18nLabel: I18nLabel,
        defaultLabel: String,
        context: I18nContext
    ): CharSequence {
        val (locale, userInterfaceType, connectorId, contextId) = context

        return if (userInterfaceType == textAndVoiceAssistant) {
            val text = i18nLabel.findLabel(locale, textChat, connectorId)
            val voice = i18nLabel.findLabel(locale, voiceAssistant, connectorId)
            if (voice != null) {
                if (text != null) {
                    val randomIndex = text.randomAlternativesIndex()
                    val t = randomText(i18nLabel, text, contextId, randomIndex)
                    val v = randomText(i18nLabel, voice, contextId, randomIndex)
                    if (t.isNotBlank() && v.isNotBlank()) {
                        TextAndVoiceTranslatedString(t, v)
                    } else {
                        t
                    }
                } else {
                    randomText(i18nLabel, voice, contextId)
                }
            } else {
                text?.let { randomText(i18nLabel, text, contextId) }
                    ?: labelWithoutUserInterface(
                        i18nLabel,
                        defaultLabel,
                        context.copy(userInterfaceType = defaultInterface)
                    )
            }
        } else {
            i18nLabel
                .findLabel(locale, userInterfaceType, connectorId)
                ?.let {
                    randomText(i18nLabel, it, contextId)
                }
                .run {
                    if (isNullOrBlank()) {
                        labelWithoutUserInterface(i18nLabel, defaultLabel, context)
                    } else {
                        this!!
                    }
                }
        }
    }

    private fun labelWithoutUserInterface(
        i18nLabel: I18nLabel,
        defaultLabel: String,
        context: I18nContext
    ): String {

        val (locale, _, connectorId) = context

        val labelWithoutUserInterface = i18nLabel.findLabel(locale, connectorId)
        return if (labelWithoutUserInterface != null) {
            labelWithoutUserInterface.label
        } else {
            val newLabel = translate(defaultLabel, defaultLocale, locale)
            i18nDAO.save(
                i18nLabel.copy(
                    i18n =
                    LinkedHashSet(
                        i18nLabel.i18n + I18nLocalizedLabel(
                            locale,
                            defaultUserInterface,
                            newLabel
                        )
                    )
                )
            )
            newLabel
        }
    }

    fun formatMessage(
        label: String,
        context: I18nContext,
        args: List<Any?>
    ): String {
        if (args.isEmpty()) {
            return label
        }
        return MessageFormat(escapeQuotes(label), context.userLocale).format(
            args.map { formatArg(it, context) }.toTypedArray(),
            StringBuffer(),
            null
        ).toString()
    }

    private fun escapeQuotes(text: String): String = text
        .replace("'", "''")
        .replace("%%", "'")

    fun translate(text: String, source: Locale, target: Locale): String {
        if (source == target) {
            return text
        }
        val t = escapeQuotes(text)
        val m = MessageFormat(t, source)
        var pattern = m.toPattern()
        val choicePrefixList: MutableList<String> = mutableListOf()
        m.formatsByArgumentIndex.forEachIndexed { i, format ->
            if (format is ChoiceFormat) {
                pattern = pattern.replace(format.toPattern(), "")
                val choiceFormat = ChoiceFormat(
                    format.limits,
                    format.formats.map { translator.translate(it as String, source, target) }.toTypedArray()
                )
                m.setFormatByArgumentIndex(i, choiceFormat)
                choicePrefixList.add("{$i,choice,}")
            }
        }
        if (choicePrefixList.isEmpty()) {
            return translator.translate(text, source, target)
        } else {
            var splitPattern = listOf(pattern)
            choicePrefixList.forEach { prefix ->
                splitPattern.forEachIndexed { i, s ->
                    val index = s.indexOf(prefix)
                    if (index != -1) {
                        val a = s.substring(0, index)
                        val b = prefix
                        val c = s.substring(index + prefix.length, s.length)
                        splitPattern = splitPattern.subList(0, i) + listOf(a) + listOf(b) + listOf(c) +
                            splitPattern.subList(i + 1, splitPattern.size)
                    }
                }
            }
            val newMessage = MessageFormat(splitPattern.map {
                if (choicePrefixList.contains(it)) {
                    it
                } else {
                    translator.translate(it.replace("''", "'"), source, target)
                }
            }.joinToString(""))
            newMessage.formats = m.formats
            return newMessage.toPattern()
        }
    }

    private fun formatArg(arg: Any?, context: I18nContext): Any? {
        val a = when (arg) {
            is String? -> arg ?: ""
            is Number? -> arg ?: -1
            is Boolean? -> if (arg == null) -1 else if (arg) 1 else 0
            is Enum<*>? -> arg?.ordinal ?: -1
            is I18nLabelValue -> translate(arg, context)
            null -> ""
            else -> Formatter().format(context.userLocale, "%s", arg).toString()
        }

        return transformArg(a, context.userLocale, context.userInterfaceType)
    }

    fun translate(
        key: String,
        namespace: String,
        category: String,
        defaultLabel: CharSequence,
        context: I18nContext
    ): CharSequence {
        return translate(
            I18nLabelValue(
                key.toLowerCase(),
                namespace,
                category.toLowerCase(),
                defaultLabel
            ),
            context
        )
    }

    private fun notTransformedKeyFromDefaultLabel(label: CharSequence): String {
        return label.substring(0, Math.min(512, label.length))
    }

    fun getKeyFromDefaultLabel(label: CharSequence): String = notTransformedKeyFromDefaultLabel(label)

    fun completeAllLabels(i18n: List<I18nLabel>): Int {
        var count = 0
        val newI18n = i18n.map { i ->
            val newLabels = i.i18n.map { label ->
                if (label.validated || !label.label.isBlank()) {
                    label
                } else {
                    val baseValue = i.findExistingLabelForOtherLocale(label.locale, label.interfaceType, label.connectorId)
                    if (!baseValue?.label.isNullOrBlank()) {
                        count++
                        label.copy(label = translate(baseValue!!.label, baseValue.locale, label.locale))
                    } else {
                        label
                    }
                }
            }
            i.copy(i18n = LinkedHashSet(newLabels))
        }
        i18nDAO.save(newI18n)
        return count
    }

    internal fun randomText(
        i18nLabel: I18nLabel,
        localized: I18nLocalizedLabel,
        contextId: String?,
        index: Int? = null
    ): String {
        return with(localized) {
            if (alternatives.isEmpty()) {
                label
            } else {
                val i = index ?: randomAlternativesIndex()
                if (i > alternatives.size) {
                    logger.warn { "not valid index $i for $this" }
                    label
                } else if (contextId == null) {
                    localized.alternative(i)
                } else {
                    val alreadyUsedIndexes = i18nDAO.getAlternativeIndexes(i18nLabel, localized, contextId)
                    val newIndex = if (alreadyUsedIndexes.size > alternatives.size) {
                        i18nDAO.deleteAlternativeIndexes(i18nLabel, localized, contextId)
                        i
                    } else if (i in alreadyUsedIndexes) {
                        var newIndex = i
                        do {
                            newIndex = if (newIndex + 1 > alternatives.size) 0 else newIndex + 1
                        } while (newIndex in alreadyUsedIndexes)
                        newIndex
                    } else {
                        i
                    }

                    i18nDAO.addAlternativeIndex(i18nLabel, localized, newIndex, contextId)
                    localized.alternative(newIndex)
                }
            }
        }
    }
}