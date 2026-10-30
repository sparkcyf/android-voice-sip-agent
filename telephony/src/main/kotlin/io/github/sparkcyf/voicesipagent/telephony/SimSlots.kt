package io.github.sparkcyf.voicesipagent.telephony

import android.content.Context
import android.telecom.PhoneAccount
import android.telecom.PhoneAccountHandle
import android.telecom.TelecomManager
import android.telephony.SubscriptionManager
import android.telephony.TelephonyManager

/** One active SIM subscription. [slot] is the logical modem slot ([android.telephony.SubscriptionInfo.getSimSlotIndex]). */
data class SimSlot(val slot: Int, val subId: Int, val carrier: String, val account: PhoneAccountHandle?) {
    override fun toString() = "$slot:$carrier(sub$subId)"
}

/** Outbound SIM when a call requests no slot: [SYSTEM], [REFUSE] or a logical slot index (>= 0). */
object DefaultSim {
    /** Android's default SIM for calls; refused when Android asks per call. */
    const val SYSTEM = -1
    const val REFUSE = -2

    fun isValid(value: Int) = value >= REFUSE

    fun id(value: Int) = when (value) {
        SYSTEM -> "system"
        REFUSE -> "refuse"
        else -> value.toString()
    }

    fun of(id: String?): Int? = when (id) {
        "system" -> SYSTEM
        "refuse" -> REFUSE
        else -> id?.toIntOrNull()?.takeIf { it >= 0 }
    }
}

/**
 * Maps logical SIM slots to Telecom phone accounts. Slots are logical modem slots, so two eSIM
 * profiles active on one eUICC (MEP) are distinct slots. Every lookup fails closed (empty/null).
 */
interface SimSlots {
    fun active(): List<SimSlot>

    fun accountFor(slot: Int): PhoneAccountHandle? = active().firstOrNull { it.slot == slot }?.account

    /** The slot of a SIM phone account, or null for other accounts. */
    fun slotOf(account: PhoneAccountHandle?): Int?

    /** The system default calling SIM, or null when the user chooses per call ("ask every time"). */
    fun defaultAccount(): PhoneAccountHandle?
}

/**
 * [SimSlots] from SubscriptionManager/Telecom. Needs READ_PHONE_STATE; missing permission or
 * services (devices without telephony) yield no SIMs.
 */
class SystemSimSlots(context: Context) : SimSlots {
    private val subs: SubscriptionManager? = context.getSystemService(SubscriptionManager::class.java)
    private val telecom: TelecomManager? = context.getSystemService(TelecomManager::class.java)
    private val telephony: TelephonyManager? = context.getSystemService(TelephonyManager::class.java)

    override fun active(): List<SimSlot> = runCatching {
        val accounts = simAccounts().associateBy { telephony!!.getSubscriptionId(it) }
        subs!!.activeSubscriptionInfoList.orEmpty().filter { it.simSlotIndex >= 0 }.map {
            SimSlot(it.simSlotIndex, it.subscriptionId, (it.displayName ?: it.carrierName ?: "").toString(),
                accounts[it.subscriptionId])
        }.sortedBy { it.slot }
    }.getOrDefault(emptyList())

    override fun slotOf(account: PhoneAccountHandle?): Int? = runCatching {
        val subId = telephony!!.getSubscriptionId(account!!)
        if (subId == SubscriptionManager.INVALID_SUBSCRIPTION_ID) null
        else SubscriptionManager.getSlotIndex(subId).takeIf { it >= 0 }
    }.getOrNull()

    override fun defaultAccount(): PhoneAccountHandle? = runCatching {
        telecom!!.getDefaultOutgoingPhoneAccount(PhoneAccount.SCHEME_TEL)?.takeIf(::isSim)
    }.getOrNull()

    private fun simAccounts(): List<PhoneAccountHandle> = telecom!!.callCapablePhoneAccounts.orEmpty().filter(::isSim)

    private fun isSim(handle: PhoneAccountHandle) = runCatching {
        telecom!!.getPhoneAccount(handle)?.hasCapabilities(PhoneAccount.CAPABILITY_SIM_SUBSCRIPTION) == true
    }.getOrDefault(false)
}
