# Sala TV

Launcher nativo leve para Android TV 11 ou superior, feito para a TCL deste atendimento. O firmware reportou Android 11/API 30. Aplicativo Java com AndroidX RecyclerView, minSdk 30 e targetSdk 35.

O dock inicial usa **Jellyfin → Netflix → YouTube → Prime Video**. Fundo preto, faixa arredondada, destaque único animado, nome do app, brilho de cor extraído do banner, relógio, data e botões de Entradas, Wi-Fi e Configurações. A grade de quatro colunas fica abaixo do dock: pressione Baixo para revelá-la. Não há blur, vídeos, anúncios, serviços em segundo plano, permissão de Internet ou consultas periódicas à rede.

## Teste no Windows

Extraia o pacote inteiro; mantenha a pasta `scripts` com todos os arquivos. Use a janela do PowerShell em que `$adb` aponta para o ADB oficial já baixado. Se abrir outra janela, redefina `$adb` com o caminho completo do `adb.exe`.

Na pasta extraída:

```powershell
& .\scripts\Install-Test.ps1 -Adb $adb
```

O script conecta em `192.168.68.50:5555`, salva o estado anterior, transfere o APK, compara SHA256, instala por `pm install -r -t`, abre a Activity explicitamente e salva a captura em `tv-records\test-...\01-initial.png`. **Não desativa o launcher do Google e mantém a HOME anterior.** Uma autorização ADB ou confirmação de instalação pode aparecer na TV. Se a instalação for recusada por verificação ou assinatura de HOME, pare e guarde a saída; não contorne a verificação nem repita com outros instaladores.

Se o Windows bloquear scripts baixados, após conferir os arquivos use estes comandos para liberar somente os scripts deste pacote e executar com política RemoteSigned apenas nesse processo:

```powershell
Get-ChildItem .\scripts\*.ps1 | Unblock-File
powershell.exe -NoProfile -ExecutionPolicy RemoteSigned -File .\scripts\Install-Test.ps1 -Adb $adb
```

Isso não altera permanentemente a política do Windows. Se uma política da organização continuar bloqueando a execução, reporte a mensagem.

### Verificação na TV real

Antes de mudar a HOME, inspecione a primeira captura. Depois teste com o controle, capturando e inspecionando o resultado entre etapas:

1. Esquerda/Direita percorrem o dock sem atravessar suas extremidades. O nome e a cor ambiente acompanham o foco.
2. Cima abre a navegação dos três botões. As extremidades não saltam para os apps.
3. Baixo a partir do dock vai para a grade. Cima/Baixo mudam uma linha e preservam a coluna; uma coluna inexistente na última linha mantém o foco.
4. Abra e retorne dos quatro apps; o último tile deve manter o foco. A Netflix estava desativada no backup original, portanto seu tile aparecerá indisponível até ser reativada.
5. Abra Entradas e teste as conexões HDMI usadas, imagem e áudio; teste também o menu físico de entradas, controle e volume.
6. Verifique Wi-Fi, Configurações e ausência de crash em `runtime-errors.txt`. Esse arquivo pode conter erros de outros apps: identifique o processo antes de atribuí-los ao launcher.

Exemplo de captura adicional:

```powershell
& $adb -s 192.168.68.50:5555 shell screencap -p /data/local/tmp/sala-check.png
& $adb -s 192.168.68.50:5555 pull /data/local/tmp/sala-check.png .\sala-check.png
```

A reinstalação do Jellyfin já foi aceita e sua tela de abertura foi capturada. Isso não valida a instalação deste novo pacote HOME, sua interface, reprodução de mídia ou troca HDMI; essas verificações exigem esta etapa na TV.

### Ativar como HOME, somente depois do teste visual

```powershell
& .\scripts\Set-Home.ps1 -Adb $adb
```

Esse comando registra os candidatos HOME, define Sala TV, confere o resolver, pressiona Home e captura o resultado. O launcher do Google ainda fica habilitado. Depois de inspecionar essa captura e testar os apps/HDMI, a etapa separada abaixo desativa o launcher antigo:

```powershell
& .\scripts\Set-Home.ps1 -Adb $adb -DisableStockLauncher
```

O script verifica se outro candidato HOME assumiu o controle e restaura o launcher anterior se detectar isso. **Não desativa o pacote de configurações nem o assistente de configuração automaticamente.** Nesta TV, os candidatos originais eram LauncherX, `setupwraith.RecoveryActivity` (com Activity desabilitada no relatório) e FallbackHome das configurações. Um crash posterior do aplicativo exige usar a restauração pelo ADB.

## Restauração e apps

```powershell
& .\scripts\Restore-TV.ps1 -Adb $adb
```

Mantenha `tv-records\original-state.json`: ele registra a HOME e os estados de LauncherX, setupwraith e Netflix antes da instalação. O script devolve esses estados e reativa BI e Logkit no estado padrão (`enabled=0`), conforme os relatórios anteriores à nossa limpeza. Os outros 23 pacotes originalmente desativados permanecem como estavam. Sala TV continua instalado e seus ajustes não são apagados. Backups não contêm os dados internos dos apps nem uma imagem completa do sistema.

Para reativar um app específico, use **o nome do pacote correto**, por exemplo Netflix:

```powershell
& $adb -s 192.168.68.50:5555 shell pm enable --user 0 com.netflix.ninja
```

Para emergências, o launcher original conhecido pode ser recuperado sem navegar pela interface:

```powershell
& $adb -s 192.168.68.50:5555 shell pm enable --user 0 com.google.android.apps.tv.launcherx
& $adb -s 192.168.68.50:5555 shell cmd package set-home-activity --user 0 com.google.android.apps.tv.launcherx/com.google.android.apps.tv.launcherx.home.HomeActivity
& $adb -s 192.168.68.50:5555 shell input keyevent KEYCODE_HOME
```

Nenhum script desinstala apps, limpa dados, muda verificadores de pacotes ou desativa entradas, Bluetooth, teclado, Play Store ou Play Services. A limpeza dos demais apps ainda depende das próximas etapas verificadas na TV. Desativar pacotes de diagnóstico não remove as recomendações do Google TV; a interface muda ao usar o novo launcher. Não foi medido ganho de desempenho.

## Personalização gratuita

**Mantenha OK pressionado em Configurações** para abrir as opções da tela inicial. Escolha os quatro apps do dock, em sequência; cancelar não altera a ordem anterior. Só aparecem apps instalados e habilitados com entrada Android TV.

Para papel de parede, escolha uma imagem pelo seletor do sistema ou volte a **Usar fundo preto**. Caso a TV não tenha seletor, abra Sala TV uma vez e envie um JPEG ao diretório privado externo do app:

```powershell
& $adb -s 192.168.68.50:5555 push "C:\caminho\imagem.jpg" /sdcard/Android/data/dev.salattv.launcher/files/wallpaper.jpg
```

Depois use **Configurações (OK longo) → Usar imagem local**. A imagem é reduzida para até 1920×1080, usa RGB565 e fica escurecida para legibilidade. Reinicie a Activity se substituir a mesma imagem enquanto ela estiver selecionada.

O nome do Wi-Fi depende da proteção de privacidade do Android. No botão Wi-Fi, escolha **Mostrar nome da rede** e conceda a permissão solicitada se desejar. O Android também pode exigir que Localização esteja ativada. Sem essa autorização, o botão continua funcionando com o texto Wi-Fi. O app não consulta coordenadas nem transmite dados.

Entradas são descobertas pelo TV Input Framework: HDMI, AV/componente e o sintonizador TCL. A troca é solicitada à Activity de TV via URI oficial. Se o firmware não expuser a entrada ou bloquear a Activity para apps comuns, o botão físico do controle continua necessário; não há tentativa de obter permissões privilegiadas.

## Compilar e instalar novamente

O diretório `source` do pacote contém o projeto Gradle completo. Pré-requisitos: JDK 17 com `javac`, Android SDK Platform 35 e Build Tools 35.0.0. Configure `ANDROID_HOME` ou `local.properties` com `sdk.dir`. O wrapper fixa Gradle 8.11.1 com SHA256; AGP 8.9.2 e RecyclerView 1.3.2 estão fixados.

No PowerShell, dentro de `source`:

```powershell
& .\scripts\Build-And-Install.ps1 -Adb $adb
```

Esse comando executa testes de navegação, lint e build antes de instalar em modo de teste. Se for usar o mesmo histórico de restauração da distribuição binária, passe `-Records` com o caminho daquela pasta `tv-records`.

O pacote de desenvolvimento inclui `.local\debug.keystore` para que recompilações mantenham a assinatura do APK entregue. É uma chave de desenvolvimento com senha padrão `android`, não serve para publicação de produção. Preserve-a para atualizar sem desinstalar. O script só gera uma chave nova se ela estiver ausente; uma assinatura diferente causa falha de atualização, e não deve ser contornada apagando dados automaticamente.

Em Linux, com o toolchain ativado:

```bash
./gradlew --no-daemon --console=plain testDebugUnitTest lintDebug assembleDebug
```

Cada tarefa na nuvem já é isolada: use o checkout existente em `/workspace/testtv`; não crie worktrees sem solicitação explícita. A nuvem não compartilha o Wi-Fi do Windows. Comandos ADB reais deste fluxo são executados no computador local.

A fonte Outfit foi obtida de Google Fonts e está incluída com sua licença SIL Open Font License em `app/src/main/assets/fonts/OFL.txt`. Os banners e ícones dos apps são carregados das instalações locais e não são distribuídos no código.

Ao concluir todas as verificações, desative a depuração na TV. Será preciso reativá-la para futuras alterações por ADB.
