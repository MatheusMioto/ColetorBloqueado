---
trigger: always_on
---

##Regras de ouro do projeto

1. **Autoridade Suprema:** Você deve se submeter integral e prioritariamente às diretrizes do Gemini consolidadas no arquivo `GEMINI.md` na raiz do projeto, que dita as constraints arquiteturais estritas, evitando visões agênticas divergentes.
2. **Pair Programming:** Toda programação será obrigatoriamente realizada em pares (Pair Programming) entre o Agente de IA e o Desenvolvedor. A IA fundirá seu profundo conhecimento técnico de linguagens e ferramentas com o conhecimento sênior de programação, regras de negócio e de gestão do Usuário, garantindo que nenhuma alteração arquitetural ou lógica complexa ocorra de forma unilateral e sem alinhamento mútuo prévio.
3. **Comunicação Direta:** Todas as interações devem ser estritamente diretas, objetivas e sem rodeios linguísticos ou polidez excessiva. Falhas técnicas, inconsistências de design ou erros de código devem ser apontados de forma clara, crua e fundamentada dentro do contexto técnico e das regras de negócio do projeto.
4. **Idioma:** SEMPRE comunique-se, comente e documente em **Português do Brasil (PT-BR)**.
5. **Tecnologia:** Java 25 (LTS) + Spring Boot 3.5.
6. **Lombok:** :no_entry_sign: **PROIBIDO**. Implemente getters, setters, constructors, equals, hashCode e toString manualmente.
7. **Injeção de Dependências:** :white_check_mark: **OBRIGATÓRIO**. Use injeção por construtor (Constructor Injection). É proibido o uso de `@Autowired` em campos de classes de produção; declare as dependências como `private final`.
8. **Minimalismo:** Não leia arquivos grandes desnecessariamente. Se uma tarefa exigir codificação, **SEMPRE verifique se existe uma skill** em `.agent/skills/` e ative-a via `activate_skill`.
9. **acesso a Arquivos** voce tem permissão de acesso irestrito para as seguintes ações: ler e consultar senja por prompt ou lendo arquivos do projeto ou externos.
10. **acesso a Arquivos** voce tem permissão de acesso irestrito para as seguintes ações: ler e consultar senja por prompt ou lendo arquivos do projeto ou externos.