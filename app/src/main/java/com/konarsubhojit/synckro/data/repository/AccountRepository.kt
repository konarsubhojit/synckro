package com.konarsubhojit.synckro.data.repository

import com.konarsubhojit.synckro.data.local.dao.AccountDao
import com.konarsubhojit.synckro.data.local.entity.AccountEntity
import com.konarsubhojit.synckro.domain.auth.Account
import com.konarsubhojit.synckro.domain.model.CloudProviderType
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Repository that coordinates account persistence between the domain layer
 * ([Account]) and Room ([AccountEntity]). All persistent I/O is logged via
 * Timber as required by the PR spec.
 */
@Singleton
class AccountRepository @Inject constructor(
    private val accountDao: AccountDao,
) {
    /**
     * Observes all accounts, mapped from [AccountEntity] to domain [Account].
     * Emits the current list and subsequent changes.
     */
    fun observeAll(): Flow<List<Account>> =
        accountDao.observeAll().map { entities -> entities.map { it.toDomain() } }

    /**
     * Fetches all accounts synchronously.
     *
     * @return A list of all accounts, or an empty list if none exist.
     */
    suspend fun getAll(): List<Account> {
        Timber.d("AccountRepository.getAll()")
        return accountDao.getAll().map { it.toDomain() }
    }

    /**
     * Fetches the account with the given id.
     *
     * @param id The account's unique identifier.
     * @return The matching account, or null if not found.
     */
    suspend fun getById(id: String): Account? {
        Timber.d("AccountRepository.getById(id=$id)")
        return accountDao.getById(id)?.toDomain()
    }

    /**
     * Inserts or updates the given account. Logs the operation via Timber.
     * Preserves the original [AccountEntity.createdAtMillis] on updates via a single SQL
     * ON CONFLICT statement, so creation-time ordering remains stable without an extra read.
     *
     * @param account The account to save.
     */
    suspend fun upsert(account: Account) {
        Timber.i("AccountRepository.upsert(id=${account.id}, provider=${account.provider}, email=${account.email})")
        accountDao.upsertPreservingCreatedAt(
            id = account.id,
            providerType = account.provider,
            displayName = account.displayName,
            email = account.email,
            createdAtMillis = System.currentTimeMillis(),
        )
    }

    /**
     * Deletes the account with the specified id. Logs the operation via Timber.
     *
     * @param id The account's unique identifier.
     */
    suspend fun delete(id: String) {
        Timber.i("AccountRepository.delete(id=$id)")
        accountDao.delete(id)
    }

    /**
     * Returns all accounts for a given provider type.
     *
     * @param providerType The cloud provider type to filter by.
     * @return A list of accounts for the specified provider.
     */
    suspend fun getByProvider(providerType: CloudProviderType): List<Account> {
        Timber.d("AccountRepository.getByProvider(providerType=$providerType)")
        return accountDao.getByProvider(providerType).map { it.toDomain() }
    }
}

/**
 * Maps a domain [Account] to a Room [AccountEntity] for use where a full entity is needed
 * (e.g. direct inserts). For upserts that should preserve [AccountEntity.createdAtMillis],
 * use [AccountDao.upsertPreservingCreatedAt] instead.
 */
private fun Account.toEntity(): AccountEntity = AccountEntity(
    id = id,
    providerType = provider,
    displayName = displayName,
    email = email,
    createdAtMillis = System.currentTimeMillis(),
)

/**
 * Maps a Room [AccountEntity] back to a domain [Account].
 */
private fun AccountEntity.toDomain(): Account = Account(
    id = id,
    provider = providerType,
    displayName = displayName,
    email = email,
)
