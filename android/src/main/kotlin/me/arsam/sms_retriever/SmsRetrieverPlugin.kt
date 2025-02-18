package me.arsam.sms_retriever

import android.app.Activity.RESULT_OK
import android.app.PendingIntent
import android.content.*
import android.content.ContentValues.TAG
import android.util.Log
import androidx.annotation.NonNull
import androidx.annotation.Nullable
import androidx.core.app.ActivityCompat.startIntentSenderForResult
import com.google.android.gms.auth.api.credentials.*
import com.google.android.gms.auth.api.credentials.HintRequest.Builder
import com.google.android.gms.auth.api.phone.SmsRetriever
import com.google.android.gms.common.ConnectionResult.RESOLUTION_REQUIRED
import com.google.android.gms.common.api.CommonStatusCodes
import com.google.android.gms.common.api.ResolvableApiException
import com.google.android.gms.common.api.Status
import com.google.android.gms.tasks.OnCompleteListener
import io.flutter.embedding.android.FlutterFragmentActivity
import io.flutter.embedding.engine.plugins.FlutterPlugin
import io.flutter.embedding.engine.plugins.activity.ActivityAware
import io.flutter.embedding.engine.plugins.activity.ActivityPluginBinding
import io.flutter.plugin.common.MethodCall
import io.flutter.plugin.common.MethodChannel
import io.flutter.plugin.common.MethodChannel.MethodCallHandler
import io.flutter.plugin.common.PluginRegistry

class SmsRetrieverPlugin : FlutterPlugin, MethodCallHandler, ActivityAware, PluginRegistry.ActivityResultListener {

    private lateinit var context: Context
    private var activity: FlutterFragmentActivity? = null

    private lateinit var channel: MethodChannel
    private var pendingResult: MethodChannel.Result? = null

    private var receiver: SmsBroadcastReceiver? = null
    private var consentReceiver: ConsentBroadcastReceiver? = null

    var sms: String? = null

    override fun onAttachedToEngine(@NonNull flutterPluginBinding: FlutterPlugin.FlutterPluginBinding) {
        context = flutterPluginBinding.applicationContext

        channel = MethodChannel(flutterPluginBinding.binaryMessenger, "ars_sms_retriever/method_ch")
        channel.setMethodCallHandler(this)
    }

    override fun onDetachedFromEngine(@NonNull binding: FlutterPlugin.FlutterPluginBinding) {
        channel.setMethodCallHandler(null)
    }

    override fun onDetachedFromActivity() {
        activity = null
        unregisterReceiver()
    }

    override fun onReattachedToActivityForConfigChanges(binding: ActivityPluginBinding) {
    }

    override fun onAttachedToActivity(binding: ActivityPluginBinding) {
        activity = binding.activity as FlutterFragmentActivity
        binding.addActivityResultListener(this)
    }

    override fun onDetachedFromActivityForConfigChanges() {
    }

    override fun onMethodCall(call: MethodCall, result: MethodChannel.Result) {
        when (call.method) {
            "getAppSignature" -> {
                val signatures = AppSignatureHelper(context).getAppSignatures()
                if (signatures.size > 0) {
                    result.success(signatures[0])
                } else {
                    result.success(null)
                }
            }
            "requestPhoneNumber" -> {
                pendingResult = result
                requestPhoneNumber()
            }
            "storePhoneNumber" -> {
                val url: String? = call.argument<String>("url")
                val phoneNumber: String? = call.argument<String>("phoneNumber")
                val credential: Credential = Credential.Builder(phoneNumber).setAccountType(url).build()

                val mCredentialsClient = Credentials.getClient(context)
                mCredentialsClient.save(credential).addOnCompleteListener { task ->
                    if (task.isSuccessful) {
                        result.success(null)
                    } else {
                        val e = task.exception
                        if (e is ResolvableApiException) {
                            // Try to resolve the save request. This will prompt the user if
                            // the credential is new.
                            if (e.statusCode == RESOLUTION_REQUIRED) {
                                try {
                                    pendingResult = result
                                    e.startResolutionForResult(activity, STORE_PHONE_NUMBER_REQUEST)
                                } catch (exception: IntentSender.SendIntentException) {
                                    // Could not resolve the request
                                    Log.e(TAG, "Failed to send resolution.", exception)
                                    result.success(null)
                                }
                            } else {
                                result.success(null)
                            }
                        } else {
                            result.success(null)
                        }
                    }
                }
            }
            "retrieveStoredPhoneNumber" -> {
                val url: String? = call.argument<String>("url")

                val mCredentialsClient: CredentialsClient = Credentials.getClient(context)
                val mCredentialRequest = CredentialRequest.Builder().setAccountTypes(url).build()
                mCredentialsClient.request(mCredentialRequest).addOnCompleteListener(
                        OnCompleteListener { task ->
                            if (task.isSuccessful) {
                                // See "Handle successful credential requests"
                                if (task.result != null) {
                                    val credential: Credential? = task.result!!.credential
                                    if (credential != null) {
                                        result.success(credential.id)
                                        return@OnCompleteListener
                                    }
                                }
                                result.success(null)
                            } else {
                                val e = task.exception
                                if (e is ResolvableApiException) {
                                    // Try to resolve the save request. This will prompt the user if
                                    // the credential is new.
                                    if (e.statusCode == RESOLUTION_REQUIRED) {
                                        try {
                                            pendingResult = result
                                            e.startResolutionForResult(activity, RETRIEVE_PHONE_NUMBER_REQUEST)
                                        } catch (exception: IntentSender.SendIntentException) {
                                            // Could not resolve the request
                                            Log.e(TAG, "Failed to send resolution.", exception)
                                            result.success(null)
                                        }
                                    } else {
                                        result.success(null)
                                    }
                                } else {
                                    result.success(null)
                                }
                            }
                        })
            }
            "deleteStoredPhoneNumber" -> {
                val url: String? = call.argument<String>("url")
                val phoneNumber: String? = call.argument<String>("phoneNumber")
                val credential: Credential = Credential.Builder(phoneNumber).setAccountType(url).build()

                val mCredentialsClient: CredentialsClient = Credentials.getClient(context)
                mCredentialsClient.delete(credential).addOnCompleteListener {
                    result.success(null)
                }
            }
            "startSmsListener" -> {
                receiver = SmsBroadcastReceiver()
                context.registerReceiver(receiver, IntentFilter(SmsRetriever.SMS_RETRIEVED_ACTION))
                val task = SmsRetriever.getClient(context).startSmsRetriever()
                task.addOnSuccessListener {
                    pendingResult = result
                }
            }
            "stopSmsListener" -> {
                try {
                    context.unregisterReceiver(receiver)
                    result.success(null)
                } catch (e: Throwable) {
                    Log.e(javaClass::getSimpleName.name, e.toString())
                }
            }
            "stopConsentSmsListener" -> {
                try {
                    context.unregisterReceiver(consentReceiver)
                    result.success(null)
                } catch (e: Throwable) {
                    Log.e(javaClass::getSimpleName.name, e.toString())
                }
            }
            "requestOneTimeConsentSms" -> {
                pendingResult = result
                consentReceiver = ConsentBroadcastReceiver()
                context.registerReceiver(consentReceiver, IntentFilter(SmsRetriever.SMS_RETRIEVED_ACTION))
                SmsRetriever.getClient(context).startSmsUserConsent(call.argument("senderPhoneNumber"))
            }
            else -> result.notImplemented()
        }
    }


    // Obtain the phone number from the result
    override fun onActivityResult(requestCode: Int, resultCode: Int, @Nullable data: Intent?): Boolean {
        when (requestCode) {
            CREDENTIAL_PICKER_REQUEST -> {
                if (resultCode == RESULT_OK) {
                    if (data != null) {
                        val credential: Credential? = data.getParcelableExtra(Credential.EXTRA_KEY)
                        if (credential != null) {
                            if (pendingResult != null) {
                                pendingResult?.success(credential.id)
                            }
                            return false
                        }
                    }
                    pendingResult?.success(null)
                }
            }
            SMS_CONSENT_REQUEST -> {
                if (resultCode == RESULT_OK && data != null) {
                    // Get SMS message content
                    val message = data.getStringExtra(SmsRetriever.EXTRA_SMS_MESSAGE)
                    // Extract one-time code from the message and complete verification
                    // `message` contains the entire text of the SMS message, so you will need
                    // to parse the string.
                    if (pendingResult != null && activity?.isDestroyed == false) {
                        pendingResult!!.success(message)
                    }
                    // send one time code to the server
                } else {
                    // Consent denied. User can type OTC manually.
                    if (pendingResult != null) {
                        pendingResult?.success(null)
                    }
                }
                pendingResult = null
            }
            STORE_PHONE_NUMBER_REQUEST -> {
                if (resultCode == RESULT_OK) {
                    if (pendingResult != null) {
                        pendingResult!!.success(null)
                    }
                } else {
                    if (pendingResult != null) {
                        pendingResult?.success(null)
                    }
                }
            }
            RETRIEVE_PHONE_NUMBER_REQUEST -> {
                if (resultCode == RESULT_OK) {
                    if (data != null) {
                        val credential: Credential? = data.getParcelableExtra(Credential.EXTRA_KEY)
                        if (credential != null) {
                            if (pendingResult != null && activity?.isDestroyed == false) {
                                pendingResult!!.success(credential.id)
                            }
                            return false
                        }
                    }
                    if (pendingResult != null) {
                        pendingResult?.success(null)
                    }
                } else {
                    pendingResult?.success(null)
                }
            }
        }
        return false
    }

    private fun requestPhoneNumber() {
        val hintRequest: HintRequest = Builder()
                .setPhoneNumberIdentifierSupported(true)
                .build()

        val intent: PendingIntent = Credentials.getClient(context).getHintPickerIntent(hintRequest)
        if (activity != null) {
            startIntentSenderForResult(activity!!, intent.intentSender, CREDENTIAL_PICKER_REQUEST, null, 0, 0, 0, null)
        }
    }

    /**
     * BroadcastReceiver to wait for SMS messages. This can be registered either
     * in the AndroidManifest or at runtime.  Should filter Intents on
     * SmsRetriever.SMS_RETRIEVED_ACTION.
     */
    inner class SmsBroadcastReceiver : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (SmsRetriever.SMS_RETRIEVED_ACTION == intent.action) {


                val extras = intent.extras

                if (extras != null) {
                    val status = extras.get(SmsRetriever.EXTRA_STATUS)

                    if (status != null) {
                        val smsRetrieverStatus = status as Status
                        when (smsRetrieverStatus.statusCode) {
                            CommonStatusCodes.SUCCESS -> {
                                // Get SMS message contents
                                val smsRetrieved = extras.get(SmsRetriever.EXTRA_SMS_MESSAGE)

                                if (smsRetrieved != null) {
                                    sms = smsRetrieved as String
                                    if (activity?.isDestroyed == false) {
                                        pendingResult?.success(sms)
                                    }

                                    pendingResult = null
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    inner class ConsentBroadcastReceiver : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (SmsRetriever.SMS_RETRIEVED_ACTION == intent.action) {
                val extras = intent.extras
                val smsRetrieverStatus = extras!!.get(SmsRetriever.EXTRA_STATUS) as Status

                when (smsRetrieverStatus.statusCode) {
                    CommonStatusCodes.SUCCESS -> {
                        // Get consent intent
                        val consentIntent = extras.getParcelable<Intent>(SmsRetriever.EXTRA_CONSENT_INTENT)
                        try {
                            // Start activity to show consent dialog to user, activity must be started in
                            // 5 minutes, otherwise you'll receive another TIMEOUT intent
                            if (this@SmsRetrieverPlugin.activity != null) {
                                this@SmsRetrieverPlugin.activity!!.startActivityForResult(consentIntent, SMS_CONSENT_REQUEST)
                            }
                        } catch (e: ActivityNotFoundException) {
                            if (activity?.isDestroyed == false) {
                                pendingResult?.success(null)
                            }
                        }
                    }
                }
            }
        }
    }

    private fun unregisterReceiver() {
        try {
            receiver?.let {
                activity?.unregisterReceiver(it)
            }

            consentReceiver?.let {
                activity?.unregisterReceiver(it)
            }
        } catch (ex : Exception) {
            Log.e(TAG, "Failed to unregister services.", ex)
        }
    }

    companion object {
        private const val CREDENTIAL_PICKER_REQUEST = 130479
        private const val SMS_CONSENT_REQUEST = 130480
        private const val STORE_PHONE_NUMBER_REQUEST = 130481
        private const val RETRIEVE_PHONE_NUMBER_REQUEST = 130482
    }
}

