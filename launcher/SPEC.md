# SPEC · launcher

Leia antes o [`AGENTS.md`](../AGENTS.md) e o [`common/SPEC.md`](../common/SPEC.md).

| | |
|---|---|
| applicationId | `com.garagemdomadeira.nwd.launcher` (ao lado do `com.android.launcher` original) |
| Pacote Java | `com.k4811.launcher` |
| Application | `LauncherApp` (SuiteApp + `PlayerHandoff.init` + `NwdSettings.init`) |
| Orientação | Home `nosensor` (tem layout vertical via grade); Apps `sensorLandscape` |

## Responsabilidade

Tela inicial e gaveta de apps. **Não toca nada**: rádio, MP3, BT, telefone, EQ e ventoinha são apps separados. O launcher só mostra estado e manda comandos.

## Componentes

| Componente | Tipo | Intents |
|---|---|---|
| `HomeActivity` | Activity (singleTask, clearTaskOnLaunch) | `MAIN` + `HOME` + `DEFAULT` + `LAUNCHER` |
| `AppsActivity` | Activity (exportada) | `com.k4811.launcher.action.APPS` |
| `overlay/VolumeOverlayService` | Service (exportado, só a prévia em modo dev) | aviso de volume por cima dos apps (SettingService tipo 14); `com.k4811.launcher.VOLUME_PREVIEW` |
| `tracker/TrackerService` | Service (iniciado pelo `LauncherApp`) | rastreamento: posição → servidor |
| `tracker/TrackerActivity` | Activity (aparece na gaveta) | estado, impressão digital do aparelho, liga/desliga comandos remotos |
| `overlay/EdgeMenuService` | Service | menu Voltar \| Vol− Vol+ \| Início ao deslizar de uma borda (`McuKeys`) |

## Tela inicial

- Grade de 6 áreas (`HomeGrid`), com o módulo de cada área vindo de `LauncherConfig.homeLayout`. `HomeModuleViews` posiciona e ajusta cada módulo ao tamanho da área.
- Módulos: Tocando agora, Relógio, Volume (`VolumeClient`/`MuteClient`), Ventoinha (lê `mcu_smart_fan_switch`), Bluetooth (`BtClient`), Imagem (`HomeImage`, sem moldura), Velocidade e Dados do GPS (`GpsClient`, só com a tela aberta).
- Barra superior: dia/noite (`NwdSettings.headlightsOn`), voltímetro (`com.nwd.action.ACTION_QUERY_CAN_BATTERY_VOLTAGE` → `ACTION_CAN_BATTERY_VOLTAGE`, extra `extra_voltage_value` = volts × 10), indicador da fonte.
- Apagar a tela: `com.nwd.action.ACTION_SET_BACKLIGHT_SWITCH` (`extra_backlight_switch`); `screen_off_after` liga isso por inatividade.
- Recria a tela no `onResume` se idioma, tema ou fonte mudaram (`lookKey`).
- Se o `launcher.json` não existia ao abrir (na partida o sdcard monta depois do launcher), espera o arquivo aparecer (`ACTION_MEDIA_MOUNTED` e conferência a cada 2 s por 2 min) e recria a tela; a pergunta de launcher padrão espera isso.
- Pergunta para virar o launcher padrão (`persist.nwd.launcher.default`) se `ask_default_launcher`.
- Segurar a área vazia abre as Configurações do Android (saída de emergência). A engrenagem abre o app de configurações da suíte (`car-setting`) e, se ele não estiver instalado, as Configurações do Android.
- **Dock na tela inicial**: o primeiro botão (Início, que não faz nada ali) é o **Vídeo** (`DockView.refresh`, só quando o app é o launcher e o app de vídeo existe). Nos outros apps o Início continua.

## "Tocando agora" (`home/HomeSession`)

| Fonte | Estado | Comandos |
|---|---|---|
| Rádio | `RadioClient` (só observando) | memória ±, liga/desliga via `PlayerHandoff.switchToRadio` |
| MP3 | `com.k4811.action.MUSIC_STATE` (e `com.android.music.metachanged/playstatechanged` do player da NWD, com `MusicBean`) | `MusicRemote.send` (`MUSIC_COMMAND`) |
| BT | `BtClient.query` + `com.bt.ACTION_BT_MUSIC_PLAY/PAUSE`, `ACTION_AVRCP_MUSIC_ID3` | `BtClient.playControl` |
| Chamada | `com.bt.ACTION_BT_*_CALL/NUMBER/NAME` | `BtClient.answer/hangup` |

A fonte vem de `mcu_current_source`. Tocar no cartão abre o app da fonte (`DockNavigator.navigate`, sem Splash).

## Botões

Dock → apps (ver common). Engrenagem → `Apps.SETTINGS`. Bloco da ventoinha → `Apps.FAN`. Bloco do Bluetooth e cartão de chamada → `Apps.PHONE`.

## Gaveta (`AppsActivity` + `home/AppCatalog`)

Todos os apps numa grade de 5 colunas que rola para baixo até o último (sem páginas), com entrada em cascata, ícone que afunda ao toque e o app abrindo com zoom a partir do ícone. Esconde teclados, o próprio launcher e apps cujo hardware não existe (bits de `Settings.System factory_feature`).

## Por cima dos apps (`overlay/`)

Começam no `LauncherApp`. Janelas `TYPE_SYSTEM_ALERT` (`SYSTEM_ALERT_WINDOW`, concedida na instalação), escala própria pelo tamanho real da tela.

- **Aviso de volume:** pílula no alto (alto-falante, barra na cor do tema, número) quando o volume muda; some em ~2 s; não pega toques. Ignora os valores dos primeiros 2 s (volume atual). Opção `show_volume_overlay`.
- **Menu da borda:** área de 30 de profundidade no meio da borda (380 de largura embaixo, 300 de altura nas laterais) com um puxador visível; deslizar 24 para dentro abre a pílula (deitada embaixo, em pé nas laterais). Voltar e Vol± = teclas falsas da MCU (`common/system/McuKeys`); Início = intent HOME. Segurar Vol± repete. Opção `edge_menu_side` (0 desligado, 1 embaixo, 2 esquerda, 3 direita), conferida a cada 3 s. Embaixo, a barra da NWD (`com.nwd.statusbarbottom`) fica por cima e pega o gesto em apps de fora.

## Regras

- Não grave configuração: o launcher só lê `launcher.json`.
- Comando para outra fonte sempre pela API do app dela (MusicRemote, BtClient, RadioClient + PlayerHandoff).

## Lacunas

- Não substitui o launcher original: os serviços dele (janela de volume, barra lateral, botão flutuante, clima) continuam no `com.android.launcher`.

## Rastreamento (`tracker/`)

Porta do app CarInfo para dentro do launcher. Como o launcher é o app de HOME, o sistema o inicia no boot e o reinicia se morrer, e o firmware da central não o mata ao dormir (o CarInfo isolado era morto no sleep da chave).

- **`TrackerService`**: serviço em primeiro plano, iniciado em `LauncherApp.onCreate`. GPS a cada N s (padrão 5), só enfileira se andou ≥ X m (padrão 10, ignora precisão pior que 50 m), ponto de "estou vivo" a cada Y min parado (padrão 10), fila em disco (`queue.jsonl`, até 20 000 pontos) enviada em lotes para `POST /api/positions` e reenviada quando a rede volta.
- **`Agent`**: canal de comandos remotos **assinados**, desligado por padrão (checkbox na `TrackerActivity`, com confirmação). A chave do aparelho (EC P-256) é gerada uma vez; a pública viaja uma vez, no pedido de cadastro (`/api/agent/enroll`), e o operador a aprova conferindo a impressão digital mostrada na tela. Depois cada contato (`/api/agent/poll`) é só assinado. Só executa pacote assinado por um operador embutido em `assets/operators/*.pem`, endereçado a este aparelho, dentro da validade (≤ 15 min) e nunca visto antes; a resposta volta assinada pelo aparelho. O shell roda com as permissões do launcher.
- **`Sig`**: ECDSA P-256 / SHA-256 e Base64 próprios, sem dependência do Android (API 17 não tem `java.util.Base64`).
- **`TrackerLog`**: registro de eventos só de acrescentar, em dois arquivos que se alternam (`tracker.log`/`tracker.old`, ~40 KB cada).
- **`TrackerActivity`**: serviço rodando?, aparelho, última posição, último envio, fila, erro, estado do cadastro, impressão digital (texto grande, agrupado de 4 em 4), últimas linhas do log. Strings em `common` (`tracker_*`), nos 8 idiomas. Entra na gaveta por exceção em `AppCatalog` (o launcher esconde o resto de si).
- **Configuração de build (ambiente, nunca no repositório):** `CARINFO_URL`, `CARINFO_TOKEN`, `CARINFO_DEVICE` viram `BuildConfig.DEFAULT_*`. Sem URL ou token o serviço roda mas não envia (a tela mostra o erro). Preferências próprias em `SharedPreferences "tracker"` (intervalo, distância, heartbeat); não usa o `launcher.json`.
- **Chaves públicas dos operadores** (`assets/operators/*.pem`) são públicas e podem ir ao repositório. Chaves privadas e o token, não.
- **Botões da `TrackerActivity`**: *Baixar atualização do app* (baixa `/dl/launcher?token=…` para `getExternalFilesDir` e pede a instalação com `ACTION_INSTALL_PACKAGE`; sem instalador padrão abre um seletor; o Android pede a confirmação, nada é instalado em silêncio) e *Enviar logs ao servidor* (`POST /api/diag`: estado, eventos e logcat do app).
- **Assinatura**: o release sai com a chave de debug (sem `keystore.properties`). Ela precisa ser **a mesma em todos os builds**, senão o Android recusa a atualização por cima (erro −7, `INSTALL_FAILED_UPDATE_INCOMPATIBLE`). No ambiente de build do servidor ela fica em `android/keystore/launcher-debug-home/` (montada como `~/.android` do container; fora do git, manter privada).
- **Reinstalação**: ao desinstalar, a chave do aparelho se perde. O servidor ainda guarda a antiga aprovada para o mesmo id e responde 401 aos contatos novos: rodar `carctl.py revoke <id>` antes de o aparelho pedir cadastro de novo e aprovar a impressão nova.
- **Testado na central (2026-11-16)**: serviço em primeiro plano com targetSdk 19 no Android 10 roda, partida no boot funciona, posições e cadastro chegam ao servidor. **Não testado**: sobrevivência ao sleep da chave e a atualização pelo botão (a 2.0.13 abria o APK no Termux; corrigido na 2.0.14, ainda a confirmar).
