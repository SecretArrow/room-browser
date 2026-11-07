# Mengapa submit email Google selalu gagal — dan bagaimana ditemukan

*Catatan teknis untuk `accounts.google.com` pada edisi WebView.
Ditulis setelah alur live E2E pertama kali HIJAU (run 685, 6 November
2026). Semua klaim di bawah didukung wire trace `RoomGoogleTrace` yang
masih ada di kode (`WebViewEngineSession` / `RequestedWithCleaner`).*

## Gejala

Sampai v1.0.201, mengisi alamat email pada form masuk Google selalu
berakhir di halaman generik **"Something went wrong"** — di perangkat
pengguna maupun di emulator CI. Halaman form email itu sendiri selalu
ter-render dengan sempurna, cookie tampak ada, dan identitas di kawat
sudah konsisten; kegagalan selalu terjadi **tepat setelah tombol
"Sign in" ditekan**.

## Akar masalah: halaman "tinggal satu hop di belakang redirect"

`ServiceLogin` tidak pernah menjawab halaman form secara langsung.
Ia menjawab **HTTP 302** dengan `Location` yang membawa state flow:

```
Location: /v3/signin/identifier?...&flowName=GlifWebSignIn
          &flowEntry=ServiceLogin&dsh=S-284351155:1793951528333924
```

Parameter **`dsh`** adalah tiket yang mengikat form ke sesi. Di
peramban biasa WebView sendiri mengikuti 302, sehingga
`window.location` berisi `dsh` dan submit berhasil.

Engine ini menyadap request untuk menulis ulang stempel
`X-Requested-With` (`RequestedWithCleaner`). Karena request dibuat
ulang via `HttpURLConnection`, 302 tidak pernah sampai ke WebView, dan
cleaner mengikuti redirect **di dalam Java** lalu menyajikan HTML
halaman identifier **di bawah URL yang lama**. Akibatnya:

- `window.location` tetap `…/ServiceLogin?hl=en…` — tanpa `dsh`,
  tanpa `flowName`, tanpa `flowEntry`;
- dokumen yang berjalan sebenarnya adalah halaman
  `/v3/signin/identifier`;
- submit modern Google bukan POST pindah halaman, melainkan XHR
  `batchexecute` yang membaca state dari lokasi halamannya sendiri;
- server tidak menemukan state flow yang sah → **"Something went
  wrong"**.

## Rantai bukti (run CI berurutan)

| Run | Perubahan | Hasil | Bukti kunci |
|---|---|---|---|
| 673/675 | baseline | gagal | dump layar: error setelah submit |
| 674/676 | `7b8edb1` capture `Set-Cookie` per-hop | tetap gagal | jar terisi (`setCookieCaptured=1`) — cookie **bukan** penyebab |
| 678 | `c773653` wire trace | gagal di *build* | lambda trace menangkap return `void` `setCookie` → `1e5b948` |
| 680 | `1e5b948` trace hidup | tetap gagal | **satu-satunya** request main-frame adalah muat awal; XHR halaman melapor `source-path=%2FServiceLogin` — telemetri Google sendiri mengakui halaman tersesat |
| 682 | `3f9e8cc` fix redirect | gagal berbeda | `source-path=/v3/signin/identifier` + `dsh=` di XHR; email `alamattest@gmail.com` **diterima** → Google kini menyapa dengan tantangan reCAPTCHA (IP datacenter CI) |
| 685 | `1808c5f` kriteria test | **hijau** | alur maju melewati langkah email; tanpa flake-retry |

## Perbaikan (`3f9e8cc`)

Redirect dibelah berdasarkan frame:

- **Main frame**: 3xx dikembalikan sebagai `null` dari
  `execute()` — WebView men-request ulang dan mengikuti `Location`
  secara native; `window.location`, omnibox, dan state flow menjadi
  jujur pada URL target; hop berikutnya masuk lagi ke
  `shouldInterceptRequest` dan tetap dibersihkan.
- **Sub-resource**: tetap diikuti manual per-hop di Java — tidak ada
  `window.location` yang perlu dilindungi, dan capture `Set-Cookie`
  per hop tetap berjalan.

## Yang sempat disangka penyebab, tapi terbantahkan

- **Identitas terbelah** (GET ber-stempel `com.android.chrome`, POST
  membawa `com.roombrowser`): setelah fix redirect — tanpa mengubah
  identitas apa pun — submit langsung diterima.
- **Cookie hilang**: run 680 membuktikan jar terisi sebelum fix kedua.
- **IP datacenter**: penyebab tantangan reCAPTCHA *setelah* email
  diterima — justru bukti submit berhasil.

## Pelajaran untuk perbaikan serupa

Menyadap dan membuat ulang request main-frame mengubah fakta yang
dilihat JavaScript (`window.location`). Setiap interceptor yang
mengikuti redirect "untuk kenyamanan" harus mengembalikan redirect
tersebut ke engine — bukan menyelesaikannya sendiri. State flow
aplikasi web modern hidup di URL, bukan hanya di cookie.
