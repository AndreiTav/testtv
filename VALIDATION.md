# Validação do pacote Sala TV 0.1.0

- Build Android `testDebugUnitTest lintDebug assembleDebug`: passou.
- JUnit: 3 testes executados, zero falhas, zero erros e zero ignorados. Cobertura de alcance de todos os tiles e limites para grades de 0 a 40 apps, movimentos verticais e extremidades da barra superior.
- Lint: zero erros e 3 avisos: orientação fixa intencional para TV; metadados adicionais de backup no Android 11 (allowBackup=false já definido); atualização completa da lista pequena de apps ao retornar à tela.
- Assinatura APK v2: verificada com apksigner. minSdk 30, targetSdk 35. Manifest sem permissão INTERNET e com Wi-Fi/localização declarados como hardware opcional.
- Scripts PowerShell: sintaxe verificada em PowerShell 7.4.13/Linux; fluxo exercitado com ADB simulado. Passaram isolamento da instalação, escolha de HOME, restauração dos estados originais, interrupção em recusa de verificação e rollback se outro candidato HOME assumir após desativar LauncherX. Isso não é uma execução no Windows nem na TV real.
- Setup de compilação reexecutado com sucesso no ambiente atual: JDK 17.0.20.1, Gradle 8.11.1, SDK 35, Build Tools 35.0.0. Checksums dos downloads oficiais e TLS mantidos.

## Evidência atual da TV real e verificações pendentes

O usuário instalou Sala TV e forneceu capturas do dock e da grade de quatro colunas. Netflix foi reativada, sua abertura foi confirmada e o banner apareceu no dock. A tecla Baixo revelou a grade. Isso confirma essas etapas de interface; não constitui medição de RAM, desempenho, reprodução dos quatro apps nem verificação completa de navegação, Wi-Fi, personalização ou HDMI.

A atribuição simples de HOME respondeu Success, mas o resolver permaneceu no LauncherX. A correção de ativação com desativação prévia de LauncherX ainda exige execução no aparelho e teste da tecla Home física. O assistente de configuração, os serviços de TV/HDMI e os demais componentes essenciais permanecem preservados. BI e Logkit foram confirmados desativados; os demais apps da limpeza ainda estão pendentes.

A distribuição contém uma chave de assinatura de desenvolvimento para recompilar atualizações compatíveis. Não use essa chave para produção. Não contém backups pessoais da TV, credenciais ADB ou capturas do usuário.

## Correção de localização do APK

O instalador agora detecta automaticamente o APK na distribuição binária, na pasta dist do repositório e na saída do build. Quatro verificações com ADB simulado passaram: os três layouts e a interrupção antes de qualquer chamada ADB quando o APK está ausente. Nenhuma nova alteração no aplicativo Android.

## Correção da ativação de HOME

Quatro cenários com ADB simulado passaram: rejeição de Success quando o resolver não muda; atribuição após desativar LauncherX; restauração de LauncherX/HOME se o assistente assumir, preservando Netflix habilitada; e interrupção antes de tocar no launcher antigo caso Sala TV esteja desativado. A regressão de instalação, restauração e recusa de APK também passou. Não são testes executados na TCL: o resultado da nova sequência depende da próxima verificação no aparelho.
