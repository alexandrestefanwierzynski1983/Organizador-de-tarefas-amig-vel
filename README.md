# Organizador de tarefas amigável

Aplicativo desktop JavaFX de lista de tarefas, pensado para manter a tela simples e previsível. A identidade visual usa um símbolo de infinito colorido, associado à neurodiversidade, e tarefas iniciais editáveis de higiene e organização diária, mais tarefas semanal e mensal. É possível escrever tarefas ou selecioná-las numa galeria de pictogramas offline; a figura e o texto podem ser alterados antes ou depois de adicionar. O modelo padrão está em [tarefas-diarias-padrao.json](./src/main/resources/tarefas-diarias-padrao.json). Há um sistema opcional de incentivo com XP, níveis e uma meta pessoal de lazer. Os dados ficam em um banco SQLite local; cada conta vê somente as próprias tarefas. O aplicativo não integra serviços de IA nem faz chamadas a APIs durante o uso: tarefas não são enviadas para a internet.

## Requisitos

- Java 21
- Maven 3.9+

## Executar

Na pasta do projeto:

```powershell
mvn javafx:run
```

Para verificar o projeto:

```powershell
mvn test
```

## Contas e segurança

Crie uma conta na tela inicial. A senha é armazenada como hash BCrypt (não é reversível) e não como texto puro. Após cinco senhas incorretas para uma conta, o acesso fica bloqueado por 15 minutos. O contador e o bloqueio são persistidos no banco local.

O banco é criado em `%USERPROFILE%\.tarefas-amigaveis\tarefas.db`.

Os títulos das tarefas são criptografados no banco com AES-256-GCM. A chave é derivada da senha da conta usando PBKDF2-HMAC-SHA-256 e salt aleatório. A senha da conta não pode ser recuperada; se ela for esquecida, as tarefas criptografadas não poderão ser abertas. Na primeira entrada de contas de versões anteriores, tarefas ainda em texto puro são migradas para o formato criptografado.

Nomes de usuário, hashes BCrypt, estado de conclusão e outros metadados do banco não são criptografados. A criptografia das tarefas protege os títulos armazenados, mas não protege contra alguém com acesso ao aplicativo enquanto a conta está desbloqueada, malware no dispositivo, cópia do banco junto com a senha, ou captura de tela. Mantenha o dispositivo protegido e faça cópias de segurança cuidadosas.

Para excluir uma conta, use **Excluir conta** na tela de tarefas, confirme a ação e informe a senha. A exclusão remove permanentemente a conta, as tarefas e o progresso associado deste dispositivo; não há recuperação.

## XP, níveis e meta de lazer

Cada conclusão concede 10 XP; o mesmo item pode conceder XP novamente quando seu período de recorrência começar de novo. Tarefas diárias renovam a cada dia, semanais a cada semana (segunda-feira a domingo) e mensais a cada mês. Reabrir uma tarefa não remove XP já conquistado, e concluir novamente durante o mesmo período não duplica a recompensa.

Tarefas únicas podem ter um prazo opcional de data e hora local. O círculo ao lado da tarefa representa o tempo restante entre a criação e o prazo; ele se esvazia conforme o prazo se aproxima e fica destacado de forma gentil nas últimas 24 horas. Depois do prazo, a tarefa continua disponível e pode ser concluída sem perda de XP ou outra penalidade. O prazo acompanha a tarefa numa exportação criptografada.

São necessários 100 XP acumulados para cada nível. O painel mostra o total, o XP da semana e do mês, e o avanço até o próximo nível. Cada conta começa com tarefas editáveis diárias, uma tarefa semanal e uma mensal. A conta também começa com uma meta de lazer de 100 XP, que pode ser personalizada para um passeio em família ou outra atividade especial. Ao alcançar a meta, o app mostra uma mensagem de celebração; não agenda nem exige que o passeio aconteça. É um incentivo, sem penalidade ou prazo.

Ao transferir tarefas para outra conta, os títulos, frequência e estado de conclusão são mantidos, mas o XP permanece vinculado à conta de origem e não é transferido.

## Transferir tarefas

Use **Exportar tarefas** para salvar um arquivo JSON criptografado e defina uma senha de transferência. O arquivo contém somente a lista (texto, conclusão, frequência e pictograma), protegida com AES-GCM; não inclui nome de usuário, identificador da conta, senha/hash de login, XP, nível ou meta de lazer. Esses dados e as listas não são enviados pela internet pelo aplicativo. Na conta de destino, importe o arquivo e informe a mesma senha. A importação acrescenta as tarefas e não apaga as existentes. Guarde a senha separadamente do arquivo; sem ela, a transferência não pode ser aberta.

O arquivo criptografado ainda contém uma versão de formato e um salt aleatório necessários para a importação; o conteúdo da lista permanece cifrado. Compartilhar o arquivo e a senha juntos reduz a proteção. A privacidade também depende de proteger o próprio dispositivo e escolher uma senha de transferência forte.

## IA e prompt injection

Não há modelo de linguagem, prompt de IA ou API de terceiros no aplicativo, então o texto das tarefas não é interpretado como instruções por uma IA e não existe uma chave de API para configurar. Isso evita exposição por chamadas remotas e elimina essa superfície de prompt injection. Se um recurso de IA for adicionado no futuro, será necessário projetar isolamento dos dados, consentimento explícito e tratamento de conteúdo não confiável antes de enviar qualquer tarefa a um modelo.
