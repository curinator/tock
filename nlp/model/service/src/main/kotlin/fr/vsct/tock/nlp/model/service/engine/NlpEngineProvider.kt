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

package fr.vsct.tock.nlp.model.service.engine

import fr.vsct.tock.nlp.core.NlpEngineType

/**
 * Implements this interface to add a new nlp engine.
 * The implementation is loaded at runtime, using the java [java.util.ServiceLoader] - you need to provide a META-INF/services/xxx file.
 */
interface NlpEngineProvider {

    /**
     * Type of nlp engine.
     */
    val type: NlpEngineType

    /**
     * [NlpEngineModelBuilder] implementation for this nlp engine.
     */
    val modelBuilder: NlpEngineModelBuilder

    /**
     * [NlpEngineModelIo] implementation for this nlp engine.
     */
    val modelIo: NlpEngineModelIo

    /**
     * Returns the intent classifier from this [IntentModelHolder].
     */
    fun getIntentClassifier(model: IntentModelHolder): IntentClassifier

    /**
     * Returns the entity classifier from this [EntityModelHolder].
     */
    fun getEntityClassifier(model: EntityModelHolder): EntityClassifier

    /**
     * Returns the tokenizer from this [TokenizerModelHolder].
     */
    fun getTokenizer(model: TokenizerModelHolder): Tokenizer


}