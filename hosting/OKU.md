# Kendi Hosting'ine Yukleme

1. Bu klasordeki 3 dosyayi (SlotinTV.cs3, manifest.json, plugins.json)
   hosting'inde rastgele/gizli bir klasore yukle, ornek:
   https://senin-domain.com/x7k2p9/

2. plugins.json icindeki url'yi kendi adresinle degistir.

3. Uygulamada Eklentiler ekranina sunu gir:
   https://senin-domain.com/x7k2p9/plugins.json

## Onemli

- HTTPS zorunlu (targetSdk 36 cleartext HTTP'yi engeller).
- GitHub'a gerek yok — herhangi bir statik dosya hosting'i calisir.
- Gizli klasor adi = basit koruma. URL'yi bilmeyen bulamaz.
- Ekstra koruma istersen .htaccess ile sifre koy:
  URL formati -> https://kullanici:sifre@senin-domain.com/x7k2p9/plugins.json
  (OkHttp user:pass@host formatini otomatik Basic Auth'a cevirir)
- Eklenti sadece com.easy.streamarchive paket adinda calisir
  (baska uygulamada kursalar bile yuklenmez).
