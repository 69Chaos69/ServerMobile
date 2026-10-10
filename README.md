# Server Mobile

Server Mobile transforma um aparelho Android em um servidor de arquivos. Acesse os arquivos compartilhados pelo navegador na rede local ou conecte dois aparelhos por WebRTC com troca manual de ofertas. O app também oferece descoberta e transferência BitTorrent.

## Recursos

- Servidor HTTP local com listagem, visualização, download e envio de arquivos.
- Escolha da pasta compartilhada pelo seletor de arquivos do Android.
- Conexões WebRTC P2P com chave e sinalização manual, sem conta ou servidor de sinalização do projeto.
- Descoberta BitTorrent por DHT, LSD e PEX, iniciada somente quando o usuário toca em **Iniciar descoberta BitTorrent**.
- Gerenciamento de conexões, transferências, registros e limite de upload.

## Como usar

1. Instale e abra o app.
2. Ao iniciar um servidor pela primeira vez, escolha pelo seletor do Android a pasta a compartilhar (por exemplo, `Downloads/ServerMobile`). O app guarda somente a autorização para essa pasta e não copia seus arquivos.
3. Ative o servidor local para compartilhar os arquivos com aparelhos na mesma rede Wi-Fi; o endereço exibido pode ser aberto em um navegador. Use **Arquivos** para consultar a pasta ou escolher outra.
4. Para WebRTC, ative o servidor P2P e troque a chave e as ofertas/respostas somente com a outra pessoa, por um canal confiável.
5. Para BitTorrent, abra **Conexão P2P**, inicie a descoberta e então importe um arquivo de conexão ou adicione um magnet.

## Permissões e privacidade

- O app solicita acesso somente à pasta escolhida por você no seletor de arquivos do Android. A autorização é persistente e pode ser revogada nas configurações do sistema.
- A permissão de notificações é opcional. Negá-la não impede o servidor de iniciar, mas pode ocultar sua notificação da gaveta de notificações; o Android ainda mostra serviços ativos nos controles do sistema.
- O servidor local usa **HTTP sem autenticação ou criptografia**. Outros aparelhos na mesma rede podem listar e baixar os arquivos compartilhados e enviar arquivos. Ative-o somente em redes confiáveis e desligue-o quando terminar.
- WebRTC usa o serviço STUN `stun.l.google.com:19302` para auxiliar a conexão. A oferta/resposta pode revelar endereços de rede; envie esses dados e a chave apenas à pessoa pretendida.
- BitTorrent comunica-se com nós DHT e peers. DHT/LSD/PEX não usam a chave do WebRTC: magnets publicados são públicos e qualquer pessoa que os obtenha pode baixar o conteúdo. Para publicar um item da pasta SAF, o app cria uma cópia privada temporária, que pode ocupar espaço adicional enquanto o magnet estiver ativo.
- A interface web servida pelo app não carrega fontes ou bibliotecas de terceiros. Conexões STUN e BitTorrent continuam dependendo dos serviços e peers indicados acima.
- O serviço em primeiro plano ajuda a manter os servidores ativos enquanto o app fica em segundo plano. O Android ainda pode encerrar o processo; nesse caso, os servidores não são reiniciados automaticamente.

## Compilar

Abra o projeto no Android Studio ou, no Windows, execute:

```powershell
.\gradlew.bat assembleDebug
```

O APK de debug é gerado em `app/build/outputs/apk/debug/`.

## Licença

O código do Server Mobile está sob a [GNU GPL versão 3 ou posterior](LICENSE). As dependências mantêm suas próprias licenças; consulte os arquivos de licença publicados pelos respectivos projetos e a lista de dependências Gradle.

## Projeto

- Código-fonte: <https://github.com/69Chaos69/ServerMobile>
- Problemas e sugestões: <https://github.com/69Chaos69/ServerMobile/issues>
