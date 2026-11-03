package pro.sketchware.activities.login;

import android.content.Intent;
import android.os.Bundle;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ProgressBar;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;

import pro.sketchware.R;
import pro.sketchware.activities.main.activities.MainActivity;
import pro.sketchware.utility.SupabaseClient;

/**
 * Tela de login/cadastro do SKET-UP.
 * Se já houver sessão salva, vai direto para a MainActivity.
 */
public class LoginActivity extends AppCompatActivity {

    private EditText emailInput;
    private EditText passwordInput;
    private Button loginButton;
    private Button signupButton;
    private ProgressBar progress;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        if (SupabaseClient.isLoggedIn(this)) {
            openMain();
            return;
        }

        setContentView(R.layout.activity_login);

        emailInput = findViewById(R.id.login_email);
        passwordInput = findViewById(R.id.login_password);
        loginButton = findViewById(R.id.login_button);
        signupButton = findViewById(R.id.signup_button);
        progress = findViewById(R.id.login_progress);

        loginButton.setOnClickListener(v -> authenticate(true));
        signupButton.setOnClickListener(v -> authenticate(false));
    }

    private void authenticate(boolean isLogin) {
        String email = emailInput.getText().toString().trim();
        String password = passwordInput.getText().toString();

        if (email.isEmpty() || password.isEmpty()) {
            Toast.makeText(this, "Preencha e-mail e senha", Toast.LENGTH_SHORT).show();
            return;
        }
        if (password.length() < 6) {
            Toast.makeText(this, "A senha precisa de pelo menos 6 caracteres", Toast.LENGTH_SHORT).show();
            return;
        }

        setLoading(true);
        SupabaseClient.Callback callback = new SupabaseClient.Callback() {
            @Override
            public void onSuccess() {
                runOnUiThread(() -> {
                    setLoading(false);
                    if (isLogin) {
                        openMain();
                    } else {
                        Toast.makeText(LoginActivity.this,
                                "Conta criada! Agora faça login.", Toast.LENGTH_LONG).show();
                    }
                });
            }

            @Override
            public void onError(String message) {
                runOnUiThread(() -> {
                    setLoading(false);
                    Toast.makeText(LoginActivity.this, message, Toast.LENGTH_LONG).show();
                });
            }
        };

        if (isLogin) {
            SupabaseClient.signIn(this, email, password, callback);
        } else {
            SupabaseClient.signUp(this, email, password, callback);
        }
    }

    private void setLoading(boolean loading) {
        progress.setVisibility(loading ? View.VISIBLE : View.GONE);
        loginButton.setEnabled(!loading);
        signupButton.setEnabled(!loading);
    }

    private void openMain() {
        startActivity(new Intent(this, MainActivity.class));
        finish();
    }
}
