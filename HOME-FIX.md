# Correção de HOME para a TCL desta sessão

Este pacote contém apenas os scripts atualizados. O APK do Sala TV já instalado permanece o mesmo.

A atribuição simples retornou Success mas manteve LauncherX. Ao desativá-lo, o resolver passou para `com.google.android.tungsten.setupwraith/.RecoveryActivity`; o script anterior restaurou LauncherX. Esse é o diagnóstico observado no aparelho.

## Ativar

Extraia em uma pasta nova e mantenha `Common.ps1`, `Set-Home.ps1` e `Restore-TV.ps1` juntos na subpasta `scripts`. Use a mesma janela PowerShell com `$adb` definido e indique a pasta do backup original:

```powershell
powershell.exe -NoProfile -ExecutionPolicy RemoteSigned -File .\scripts\Set-Home.ps1 -Adb $adb -Records "$sala\tv-records" -DisableStockLauncher -DisableSetupRecovery
```

O script exige que a configuração inicial da TV esteja concluída, lê o estado efetivo da Activity no usuário 0, adiciona esse estado ao backup e só então desativa **RecoveryActivity** e **LauncherX**. O restante do assistente, configurações, HDMI, Bluetooth, teclado e demais serviços ficam preservados. A Netflix continua habilitada. Cada comando pm usa uma chamada shell separada.

Após atribuir HOME, injeta a tecla Home, confere o resolver e salva `02-without-stock.png` na nova pasta `home-...` dentro do histórico original. Uma falha tenta restaurar os dois estados anteriores e a HOME anterior, preservando os demais apps. Quando uma etapa de restauração falha, o erro é registrado e as demais restaurações ainda são tentadas.

Confira a captura, pressione Home no controle físico e teste o menu de entradas e os dispositivos HDMI. O novo fluxo foi testado com ADB simulado; isso não confirma seu resultado na TCL até essa verificação.

## Restaurar

Use estes mesmos scripts e o mesmo histórico:

```powershell
powershell.exe -NoProfile -ExecutionPolicy RemoteSigned -File .\scripts\Restore-TV.ps1 -Adb $adb -Records "$sala\tv-records"
```

A restauração completa devolve HOME, o estado original de LauncherX e os estados do backup, inclusive o override exato de RecoveryActivity. Ela também reverte a reativação da Netflix ao seu estado original desativado e devolve BI/Logkit ao padrão. É diferente do rollback de uma tentativa de HOME, que mantém a Netflix habilitada.

O arquivo `original-state.json` é ampliado com `ComponentStates` sem substituir os estados já registrados. A cópia anterior do JSON e os diagnósticos do assistente ficam na pasta da tentativa. Preserve esse histórico; não publique seus backups ou capturas no GitHub.
