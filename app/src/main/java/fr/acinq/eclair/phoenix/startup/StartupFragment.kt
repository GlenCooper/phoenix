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

package fr.acinq.eclair.phoenix.startup

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.lifecycle.Observer
import androidx.navigation.fragment.findNavController
import fr.acinq.eclair.phoenix.AppKitModel
import fr.acinq.eclair.phoenix.StartupState
import fr.acinq.eclair.phoenix.BaseFragment
import fr.acinq.eclair.phoenix.R
import fr.acinq.eclair.phoenix.databinding.FragmentStartupBinding
import fr.acinq.eclair.phoenix.security.PinDialog

class StartupFragment : BaseFragment() {

  private lateinit var mBinding: FragmentStartupBinding

  private var mPinDialog: PinDialog? = null

  override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
    mBinding = FragmentStartupBinding.inflate(inflater, container, false)
    mBinding.lifecycleOwner = this
    return mBinding.root
  }

  override fun onActivityCreated(savedInstanceState: Bundle?) {
    super.onActivityCreated(savedInstanceState)
    mBinding.appKitModel = appKit
    appKit.startupState.observe(viewLifecycleOwner, Observer {
      log.info("startup is now $it")
      startNodeIfNeeded()
    })
  }

  private fun startNodeIfNeeded() {
    if (appKit.startupState.value == StartupState.OFF && !appKit.isKitReady()) {
      mPinDialog?.show()
    }
  }

  override fun onStop() {
    super.onStop()
    mPinDialog?.dismiss()
  }

  override fun handleKit() {
    if (appKit.isKitReady()) {
      findNavController().navigate(R.id.action_startup_to_main)
    }
  }

  override fun onStart() {
    super.onStart()
    if (mPinDialog == null) {
      mPinDialog = getPinDialog(object : PinDialog.PinDialogCallback {
        override fun onPinConfirm(dialog: PinDialog, pinCode: String) {
          if (context != null) {
            appKit.startAppKit(context!!, pinCode)
          }
          dialog.dismiss()
        }

        override fun onPinCancel(dialog: PinDialog) {}
      })
    }
    startNodeIfNeeded()
  }
}