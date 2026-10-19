package com.noahwallet.services.storage

import java.io.File

class WalletDataLocation(filesDirectory: File, variant: String) {
    val directory = File(filesDirectory, "noah-data-$variant")
    val database = File(directory, "db.sqlite")
    val databaseExists: Boolean get() = database.exists()
}
