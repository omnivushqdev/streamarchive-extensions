# SlotinTV - StreamArchive Eklentisi

30slotintv.live icin canli mac ve TV kanali eklentisi.

## GitHub'a Yukleme

1. Bu klasoru bir GitHub reposuna yukle (SlotinTV.cs3, manifest.json, plugins.json).
2. `plugins.json` icindeki `url` alanini kendi raw adresinle degistir:
   `https://raw.githubusercontent.com/KULLANICI_ADI/REPO_ADI/main/SlotinTV.cs3`

## Uygulamada Kullanim

1. StreamArchive > Eklentiler ekranina git.
2. Repo URL kutusuna plugins.json'un raw adresini yapistir:
   `https://raw.githubusercontent.com/KULLANICI_ADI/REPO_ADI/main/plugins.json`
3. "Repo Ekle" de, listede "Slotin TV" cikar, kur ve aktif provider olarak sec.

## Kaynak

Provider kaynak kodu: StreamArchive projesi icinde
`app/src/main/java/com/easy/streamarchive/core/extensions/SlotintvProvider.kt`
