package cli.commands

import zio.*

import _root_.config.entity.ConfigRepository
import shared.errors.PersistenceError

object ConfigCommand:

  def listSettings: ZIO[ConfigRepository, PersistenceError, String] =
    ConfigRepository.getAllSettings.map { rows =>
      if rows.isEmpty then "No settings configured."
      else
        val maxKeyLen = rows.map(_.key.length).max
        rows.map { row =>
          val padded = row.key.padTo(maxKeyLen, ' ')
          s"  $padded  =  ${row.value}"
        }.mkString("\n")
    }

  def setSetting(key: String, value: String): ZIO[ConfigRepository, PersistenceError, String] =
    ConfigRepository.upsertSetting(key, value).as(s"Set $key = $value")
