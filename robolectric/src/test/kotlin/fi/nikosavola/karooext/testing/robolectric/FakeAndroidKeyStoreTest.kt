package fi.nikosavola.karooext.testing.robolectric

import android.app.Application
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import androidx.test.core.app.ApplicationProvider
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.spec.GCMParameterSpec
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [31, 34])
class FakeAndroidKeyStoreTest {
  @Before
  fun setUp() {
    FakeAndroidKeyStore.reset()
    FakeAndroidKeyStore.install()
  }

  private fun generate(alias: String) {
    val spec =
      KeyGenParameterSpec.Builder(
          alias,
          KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
        )
        .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
        .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
        .build()
    KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").run {
      init(spec)
      generateKey()
    }
  }

  private fun keyStore() = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }

  @Test
  fun `a generated key can be found by alias and used to encrypt and decrypt`() {
    generate("master")

    val store = keyStore()
    assertTrue(store.containsAlias("master"))
    val key = store.getKey("master", null)
    val cipher = Cipher.getInstance("AES/GCM/NoPadding")
    cipher.init(Cipher.ENCRYPT_MODE, key)
    val sealed = cipher.doFinal("secret".toByteArray())
    val open = Cipher.getInstance("AES/GCM/NoPadding")
    open.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(128, cipher.iv))

    assertArrayEquals("secret".toByteArray(), open.doFinal(sealed))
  }

  @Test
  fun `encrypted shared preferences work on top of it`() {
    val app = ApplicationProvider.getApplicationContext<Application>()
    val master = MasterKey.Builder(app).setKeyScheme(MasterKey.KeyScheme.AES256_GCM).build()

    val prefs =
      EncryptedSharedPreferences.create(
        app,
        "secret",
        master,
        EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
        EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
      )
    prefs.edit().putString("token", "abc").commit()

    assertEquals("abc", prefs.getString("token", null))
  }

  @Test
  fun `reset forgets keys and install can be called again`() {
    generate("a")
    FakeAndroidKeyStore.reset()
    FakeAndroidKeyStore.install()

    assertFalse(keyStore().containsAlias("a"))
    generate("b")
    assertEquals(listOf("b"), keyStore().aliases().toList())
  }
}
