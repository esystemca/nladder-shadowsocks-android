/*******************************************************************************
 *                                                                             *
 *  Copyright (C) 2026 by Shadowsocks-Android                                 *
 *                                                                             *
 *  This program is free software: you can redistribute it and/or modify       *
 *  it under the terms of the GNU General Public License as published by       *
 *  the Free Software Foundation, either version 3 of the License, or          *
 *  (at your option) any later version.                                        *
 *                                                                             *
 *  This program is distributed in the hope that it will be useful,            *
 *  but WITHOUT ANY WARRANTY; without even the implied warranty of             *
 *  MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the              *
 *  GNU General Public License for more details.                               *
 *                                                                             *
 *  You should have received a copy of the GNU General Public License          *
 *  along with this program. If not, see <http://www.gnu.org/licenses/>.       *
 *                                                                             *
 *******************************************************************************/

package com.github.shadowsocks

import android.os.Bundle
import android.view.View
import android.widget.ProgressBar
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.github.shadowsocks.auth.AuthManager
import com.github.shadowsocks.database.ProfileManager
import com.google.android.material.button.MaterialButton
import com.google.android.material.textfield.TextInputEditText
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class LoginActivity : AppCompatActivity() {
    private lateinit var emailInput: TextInputEditText
    private lateinit var passwordInput: TextInputEditText
    private lateinit var loginButton: MaterialButton
    private lateinit var progressBar: ProgressBar
    private lateinit var errorText: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.layout_login)

        emailInput = findViewById(R.id.email)
        passwordInput = findViewById(R.id.password)
        loginButton = findViewById(R.id.login_button)
        progressBar = findViewById(R.id.progress_bar)
        errorText = findViewById(R.id.error_text)

        loginButton.setOnClickListener { performLogin() }
    }

    private fun performLogin() {
        val email = emailInput.text?.toString()?.trim().orEmpty()
        val password = passwordInput.text?.toString()?.trim().orEmpty()

        if (email.isEmpty()) {
            errorText.text = getString(R.string.email_required)
            errorText.visibility = View.VISIBLE
            return
        }
        if (password.isEmpty()) {
            errorText.text = getString(R.string.password_required)
            errorText.visibility = View.VISIBLE
            return
        }

        errorText.visibility = View.GONE
        progressBar.visibility = View.VISIBLE
        loginButton.isEnabled = false

        lifecycleScope.launch(Dispatchers.IO) {
            val result = AuthManager.login(email, password)
            withContext(Dispatchers.Main) {
                if (result.isSuccess) {
                    ProfileManager.reloadProfiles()
                    setResult(RESULT_OK)
                    finish()
                } else {
                    progressBar.visibility = View.GONE
                    loginButton.isEnabled = true
                    errorText.text = result.exceptionOrNull()?.message?.takeIf { it.isNotBlank() } ?: getString(R.string.login_failed)
                    errorText.visibility = View.VISIBLE
                }
            }
        }
    }
}
