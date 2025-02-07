/*
 * Copyright 2024 ACINQ SAS
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package fr.acinq.phoenix.android.payments.offer

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.CreationExtras
import fr.acinq.lightning.Lightning
import fr.acinq.lightning.MilliSatoshi
import fr.acinq.lightning.db.LightningOutgoingPayment
import fr.acinq.lightning.io.OfferNotPaid
import fr.acinq.lightning.io.PaymentNotSent
import fr.acinq.lightning.io.PaymentSent
import fr.acinq.lightning.payment.OutgoingPaymentFailure
import fr.acinq.lightning.wire.OfferTypes
import fr.acinq.phoenix.android.PhoenixApplication
import fr.acinq.phoenix.android.utils.datastore.UserPrefsRepository
import fr.acinq.phoenix.data.ContactInfo
import fr.acinq.phoenix.managers.ContactsManager
import fr.acinq.phoenix.managers.NodeParamsManager
import fr.acinq.phoenix.managers.PeerManager
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import org.slf4j.LoggerFactory
import kotlin.time.Duration.Companion.seconds

sealed class OfferState {
    data object Init : OfferState()
    data object FetchingInvoice : OfferState()
    sealed class Complete : OfferState() {
        data class SendingOffer(val payment: LightningOutgoingPayment) : Complete()
        sealed class Failed : Complete() {
            data class Error(val throwable: Throwable) : Failed()
            data object CouldNotGetInvoice : Failed()
            data class PaymentNotSent(val reason: OutgoingPaymentFailure) : Failed()
            data object PayerNoteTooLong : Failed()
        }
    }
}

class SendOfferViewModel(
    val offer: OfferTypes.Offer,
    val peerManager: PeerManager,
    val nodeParamsManager: NodeParamsManager,
    val contactsManager: ContactsManager,
    val userPrefs: UserPrefsRepository,
) : ViewModel() {
    private val log = LoggerFactory.getLogger(this::class.java)

    var state by mutableStateOf<OfferState>(OfferState.Init)
    private var useRandomKey by mutableStateOf<Boolean?>(null)

    init {
        viewModelScope.launch {
            // if the offer matches a contact and user has checked the offer-key-for-contact preference, then use the offer key. Otherwise, random key.
            val useOfferKeyForContacts = userPrefs.getUseOfferKeyForContacts.first()
            useRandomKey = if (!useOfferKeyForContacts) {
                true
            } else {
                val contact = contactsManager.getContactForOffer(offer)
                contact == null
            }
        }
    }

    fun sendOffer(amount: MilliSatoshi, message: String, offer: OfferTypes.Offer) {
        if (state is OfferState.FetchingInvoice) return
        state = OfferState.FetchingInvoice

        viewModelScope.launch(Dispatchers.Default + CoroutineExceptionHandler { _, e ->
            log.error("error when paying offer payment: ", e)
        }) {
            val payerKey = when (useRandomKey) {
                null -> return@launch
                true -> Lightning.randomKey()
                false -> nodeParamsManager.defaultOffer().payerKey
            }
            val peer = peerManager.getPeer()
            val payerNote = message.takeIf { it.isNotBlank() }
            log.info("sending amount=$amount random=$useRandomKey message=$message for offer=$offer")
            val paymentResult = peer.payOffer(
                amount = amount,
                offer = offer,
                payerKey = payerKey,
                payerNote = payerNote,
                fetchInvoiceTimeout = 30.seconds,
                // FIXME: this method should accept a trampolineFees parameter
            )
            when (paymentResult) {
                is OfferNotPaid -> state = OfferState.Complete.Failed.CouldNotGetInvoice
                is PaymentNotSent -> state = OfferState.Complete.Failed.PaymentNotSent(paymentResult.reason)
                is PaymentSent -> state = OfferState.Complete.SendingOffer(paymentResult.payment)
            }
        }
    }

    class Factory(
        private val offer: OfferTypes.Offer,
        private val peerManager: PeerManager,
        private val nodeParamsManager: NodeParamsManager,
        private val contactsManager: ContactsManager,
    ) : ViewModelProvider.Factory {
        override fun <T : ViewModel> create(modelClass: Class<T>, extras: CreationExtras): T {
            val application = checkNotNull(extras[ViewModelProvider.AndroidViewModelFactory.APPLICATION_KEY] as? PhoenixApplication)
            @Suppress("UNCHECKED_CAST")
            return SendOfferViewModel(offer, peerManager, nodeParamsManager, contactsManager, application.userPrefs) as T
        }
    }
}