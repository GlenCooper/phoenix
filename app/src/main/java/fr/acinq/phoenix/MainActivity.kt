/*
 * Copyright 2019 ACINQ SAS
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

package fr.acinq.phoenix

import android.content.Context
import android.content.Intent
import android.content.pm.ActivityInfo
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkRequest
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.View
import androidx.appcompat.app.AppCompatActivity
import androidx.databinding.DataBindingUtil
import androidx.lifecycle.Observer
import androidx.lifecycle.ViewModelProvider
import androidx.navigation.NavController
import androidx.navigation.findNavController
import fr.acinq.eclair.io.PayToOpenRequestEvent
import fr.acinq.eclair.payment.PaymentFailed
import fr.acinq.eclair.payment.PaymentReceived
import fr.acinq.eclair.payment.PaymentSent
import fr.acinq.phoenix.databinding.ActivityMainBinding
import fr.acinq.phoenix.paymentdetails.PaymentDetailsFragment
import fr.acinq.phoenix.receive.ReceiveWithOpenDialogFragmentDirections
import fr.acinq.phoenix.send.ReadInputFragmentDirections
import fr.acinq.phoenix.utils.Prefs
import fr.acinq.phoenix.utils.ThemeHelper
import org.slf4j.Logger
import org.slf4j.LoggerFactory


class MainActivity : AppCompatActivity() {

  val log: Logger = LoggerFactory.getLogger(MainActivity::class.java)
  private lateinit var mBinding: ActivityMainBinding
  private lateinit var appKit: AppKitModel

  private val networkCallback = object : ConnectivityManager.NetworkCallback() {
    override fun onAvailable(network: Network) {
      super.onAvailable(network)
      log.info("network available")
      appKit.networkInfo.postValue(appKit.networkInfo.value?.copy(networkConnected = true))
      appKit.reconnect()
    }

    override fun onLosing(network: Network?, maxMsToLive: Int) {
      super.onLosing(network, maxMsToLive)
      log.info("losing network....")
    }

    override fun onUnavailable() {
      super.onUnavailable()
      log.info("network unavailable")
    }

    override fun onLost(network: Network) {
      super.onLost(network)
      log.info("network lost")
      appKit.networkInfo.postValue(appKit.networkInfo.value?.copy(networkConnected = false))
    }
  }

  private val navigationCallback = NavController.OnDestinationChangedListener { _, destination, args ->
    log.debug("destination=${destination.id}, args=$args")
    appKit.currentNav.postValue(destination.id)
  }

  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    ThemeHelper.applyTheme(Prefs.getTheme(applicationContext))
    if (Build.VERSION.SDK_INT == Build.VERSION_CODES.N) {
      requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
    }
    mBinding = DataBindingUtil.setContentView(this, R.layout.activity_main)
    appKit = ViewModelProvider(this).get(AppKitModel::class.java)
    appKit.currentNav.observe(this, Observer {
      handleNetworkAlert()
    })
    appKit.networkInfo.observe(this, Observer {
      handleNetworkAlert()
    })
    appKit.navigationEvent.observe(this, Observer {
      when (it) {
        is PayToOpenRequestEvent -> {
          val action = ReceiveWithOpenDialogFragmentDirections.globalActionAnyToReceiveWithOpen(
            amountMsat = it.payToOpenRequest().amountMsat().toLong(),
            fundingSat = it.payToOpenRequest().fundingSatoshis().toLong(),
            feeSat = it.payToOpenRequest().feeSatoshis().toLong(),
            paymentHash = it.payToOpenRequest().paymentHash().toString())
          findNavController(R.id.nav_host_main).navigate(action)
        }
        is PaymentSent -> {
          val action = NavGraphMainDirections.globalActionAnyToPaymentDetails(PaymentDetailsFragment.OUTGOING, it.id().toString(), fromEvent = true)
          findNavController(R.id.nav_host_main).navigate(action)
        }
        is PaymentFailed -> {
          val action = NavGraphMainDirections.globalActionAnyToPaymentDetails(PaymentDetailsFragment.OUTGOING, it.id().toString(), fromEvent = true)
          findNavController(R.id.nav_host_main).navigate(action)
        }
        is PaymentReceived -> {
          val action = NavGraphMainDirections.globalActionAnyToPaymentDetails(PaymentDetailsFragment.INCOMING, it.paymentHash().toString(), fromEvent = true)
          findNavController(R.id.nav_host_main).navigate(action)
        }
        else -> log.info("unhandled navigation event $it")
      }
    })
  }

  override fun onStart() {
    super.onStart()
    findNavController(R.id.nav_host_main).addOnDestinationChangedListener(navigationCallback)
    val connectivityManager = applicationContext.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager?
    connectivityManager?.registerNetworkCallback(NetworkRequest.Builder().build(), networkCallback)
  }

  override fun onNewIntent(intent: Intent) {
    super.onNewIntent(intent)
    readURIIntent(intent)
  }

  private fun readURIIntent(intent: Intent) {
    val data = intent.data
    if (data != null && data.scheme != null) {
      when (data.scheme) {
        "bitcoin", "lightning" -> {
          findNavController(R.id.nav_host_main).navigate(ReadInputFragmentDirections.globalActionAnyToReadInput(payload = data.toString()))
        }
        else -> log.info("unhandled payment scheme $data")
      }
    }
  }

  // list pages where the connectivity alert will be shown
  private val networkAlertScreens = setOf(R.id.main_fragment, R.id.receive_fragment, R.id.send_fragment, R.id.channels_list_fragment, R.id.mutual_close_fragment, R.id.force_close_fragment)

  private fun handleNetworkAlert() {
    val currentNav = appKit.currentNav.value
    val networkInfo = appKit.networkInfo.value
    if (networkAlertScreens.contains(currentNav) && networkInfo != null) {
      if (!networkInfo.networkConnected) {
        mBinding.alertBlockingMessage.text = getString(R.string.main_alert_network_lost)
        mBinding.alertBlockingButton.setOnClickListener { startActivity(Intent(Settings.ACTION_WIRELESS_SETTINGS)) }
        mBinding.alertBlocking.visibility = View.VISIBLE
      } else if (networkInfo.electrumServer == null) {
        mBinding.alertBlockingMessage.text = getString(R.string.main_alert_electrum_connecting)
        mBinding.alertBlockingButton.setOnClickListener { findNavController(R.id.nav_host_main).navigate(R.id.global_action_any_to_electrum) }
        mBinding.alertBlocking.visibility = View.VISIBLE
      } else if (!networkInfo.lightningConnected) {
        mBinding.alertBlockingMessage.text = getString(R.string.main_alert_lightning_connection_lost)
        mBinding.alertBlocking.visibility = View.VISIBLE
      } else {
        mBinding.alertBlocking.visibility = View.GONE
      }
    } else {
      mBinding.alertBlocking.visibility = View.GONE
    }
  }

  override fun onStop() {
    super.onStop()
    findNavController(R.id.nav_host_main).removeOnDestinationChangedListener(navigationCallback)
    val connectivityManager = applicationContext.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager?
    connectivityManager?.unregisterNetworkCallback(networkCallback)
  }

  override fun onDestroy() {
    super.onDestroy()
    log.info("main activity destroyed")
  }

  fun getActivityThis(): Context {
    return this@MainActivity
  }
}
