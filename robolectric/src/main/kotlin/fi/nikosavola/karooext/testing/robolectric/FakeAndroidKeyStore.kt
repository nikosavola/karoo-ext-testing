package fi.nikosavola.karooext.testing.robolectric

import android.security.keystore.KeyGenParameterSpec
import java.io.InputStream
import java.io.OutputStream
import java.security.Key
import java.security.KeyStoreSpi
import java.security.Provider
import java.security.SecureRandom
import java.security.Security
import java.security.cert.Certificate
import java.security.spec.AlgorithmParameterSpec
import java.util.Collections
import java.util.Date
import java.util.concurrent.ConcurrentHashMap
import javax.crypto.KeyGeneratorSpi
import javax.crypto.SecretKey
import javax.crypto.spec.SecretKeySpec

private const val PROVIDER = "AndroidKeyStore"
private const val KEY_BYTES = 32

/**
 * Robolectric has no `AndroidKeyStore` provider, so code that builds `EncryptedSharedPreferences`
 * or a `MasterKey` in `onCreate` crashes before the test starts. [install] registers just enough of
 * one: AES keys by alias, kept in memory, for `KeyGenerator` and `KeyStore`. It is not secure and
 * not a model of the real keystore; it only lets that setup code run.
 *
 * Call [install] from the test setup before the extension is created, for example in a `@Before`.
 * Providers are JVM-global but each Robolectric sandbox loads its own copy of this class, so
 * [install] always replaces whatever is registered.
 */
object FakeAndroidKeyStore {
  private val keys = ConcurrentHashMap<String, SecretKey>()

  /** Registers the in-memory provider, replacing any earlier one. */
  fun install() {
    Security.removeProvider(PROVIDER)
    Security.addProvider(
      object : Provider(PROVIDER, 1.0, "in-memory test keystore") {
        init {
          put("KeyStore.$PROVIDER", Store::class.java.name)
          put("KeyGenerator.AES", Generator::class.java.name)
        }
      }
    )
  }

  /** Forgets every key, so a test starts without the master key an earlier one created. */
  fun reset() = keys.clear()

  /** Key store half of the provider; public only so the security framework can instantiate it. */
  @Suppress("TooManyFunctions")
  class Store : KeyStoreSpi() {
    /** Returns the key stored under [alias], if any. */
    override fun engineGetKey(alias: String, password: CharArray?): Key? = keys[alias]

    /** Keys here have no certificate chain. */
    override fun engineGetCertificateChain(alias: String): Array<Certificate>? = null

    /** Keys here have no certificate. */
    override fun engineGetCertificate(alias: String): Certificate? = null

    /** A key has a creation date once it exists. */
    override fun engineGetCreationDate(alias: String): Date? =
      if (keys.containsKey(alias)) Date() else null

    /** Stores a secret [key] under [alias]. */
    override fun engineSetKeyEntry(
      alias: String,
      key: Key,
      password: CharArray?,
      chain: Array<out Certificate>?,
    ) {
      keys[alias] = key as SecretKey
    }

    /** Wrapped keys are not supported and are ignored. */
    override fun engineSetKeyEntry(alias: String, key: ByteArray, chain: Array<out Certificate>?) =
      Unit

    /** Certificates are not supported and are ignored. */
    override fun engineSetCertificateEntry(alias: String, cert: Certificate) = Unit

    /** Removes the key under [alias]. */
    override fun engineDeleteEntry(alias: String) {
      keys.remove(alias)
    }

    /** Lists the aliases of every stored key. */
    override fun engineAliases(): java.util.Enumeration<String> =
      Collections.enumeration(ArrayList(keys.keys))

    /** Whether a key exists under [alias]. */
    override fun engineContainsAlias(alias: String) = keys.containsKey(alias)

    /** The number of stored keys. */
    override fun engineSize() = keys.size

    /** Whether [alias] names a key; every entry is one. */
    override fun engineIsKeyEntry(alias: String) = keys.containsKey(alias)

    /** No entry is a certificate. */
    override fun engineIsCertificateEntry(alias: String) = false

    /** No certificate maps to an alias. */
    override fun engineGetCertificateAlias(cert: Certificate): String? = null

    /** The keystore is memory-only, so storing does nothing. */
    override fun engineStore(stream: OutputStream?, password: CharArray?) = Unit

    /** The keystore is memory-only, so loading does nothing. */
    override fun engineLoad(stream: InputStream?, password: CharArray?) = Unit
  }

  /** Key generator half of the provider; public only so the security framework can create it. */
  class Generator : KeyGeneratorSpi() {
    private var alias: String? = null

    /** Android keys are always generated from a `KeyGenParameterSpec`. */
    override fun engineInit(random: SecureRandom?) = error(NEEDS_SPEC)

    /** Android keys are always generated from a `KeyGenParameterSpec`. */
    override fun engineInit(keysize: Int, random: SecureRandom?) = error(NEEDS_SPEC)

    /** Remembers the alias in [params], which must be a `KeyGenParameterSpec`. */
    override fun engineInit(params: AlgorithmParameterSpec, random: SecureRandom?) {
      alias = (params as KeyGenParameterSpec).keystoreAlias
    }

    /** Creates a random AES key and stores it under the alias given to `init`. */
    override fun engineGenerateKey(): SecretKey {
      val key = SecretKeySpec(ByteArray(KEY_BYTES).also { SecureRandom().nextBytes(it) }, "AES")
      keys[checkNotNull(alias) { NEEDS_SPEC }] = key
      return key
    }

    private companion object {
      const val NEEDS_SPEC = "AndroidKeyStore keys need a KeyGenParameterSpec"
    }
  }
}
