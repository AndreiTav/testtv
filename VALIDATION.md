# Validação do pacote Sala TV 0.1.0

- Build Android `testDebugUnitTest lintDebug assembleDebug`: passou.
- JUnit: 3 testes executados, zero falhas, zero erros e zero ignorados. Cobertura de alcance de todos os tiles e limites para grades de 0 a 40 apps, movimentos verticais e extremidades da barra superior.
- Lint: zero erros e 3 avisos: orientação fixa intencional para TV; metadados adicionais de backup no Android 11 (allowBackup=false já definido); atualização completa da lista pequena de apps ao retornar à tela.
- Assinatura APK v2: verificada com apksigner. minSdk 30, targetSdk 35. Manifest sem permissão INTERNET e com Wi-Fi/localização declarados como hardware opcional.
- Scripts PowerShell: sintaxe verificada em PowerShell 7.4.13/Linux; fluxo exercitado com ADB simulado. Passaram isolamento da instalação, escolha de HOME, restauração dos estados originais, interrupção em recusa de verificação e rollback se outro candidato HOME assumir após desativar LauncherX. Isso não é uma execução no Windows nem na TV real.
- Setup de compilação reexecutado com sucesso no ambiente atual: JDK 17.0.20.1, Gradle 8.11.1, SDK 35, Build Tools 35.0.0. Checksums dos downloads oficiais e TLS mantidos.

## Ainda exige a TV real

Instalação do novo pacote HOME, layout e animações, navegação pelo controle, abertura/reprodução dos apps, nome do Wi-Fi, personalização, troca HDMI, HOME padrão, consumo de RAM e ganho de desempenho não foram verificados neste APK. O primeiro script só instala e abre para esse teste. Não desative o launcher antigo antes da revisão das capturas.

A reinstalação dos quatro APKs originais do Jellyfin foi aceita no Windows do usuário e sua tela de abertura foi capturada. Os pacotes com.tcl.bi e com.tcl.logkit foram confirmados desativados. Os demais apps da limpeza ainda estão pendentes; Netflix continua no estado desativado do backup até uma etapa explícita de reativação.

A distribuição contém uma chave de assinatura de desenvolvimento para recompilar atualizações compatíveis. Não use essa chave para produção. Não contém backups pessoais da TV, credenciais ADB ou capturas do usuário.

## Correção de localização do APK

O instalador agora detecta automaticamente o APK na distribuição binária, na pasta dist do repositório e na saída do build. Quatro verificações com ADB simulado passaram: os três layouts e a interrupção antes de qualquer chamada ADB quando o APK está ausente. Nenhuma nova alteração no aplicativo Android.
