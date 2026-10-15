package catalogador.ia;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/**
 * Un servicio de IA que el Catalogador sabe usar. Casi todos hablan el
 * «dialecto OpenAI» (POST /chat/completions con «Authorization: Bearer clave»);
 * Anthropic (Claude) usa el suyo (POST /messages con «x-api-key») y Ollama se
 * usa con su API propia (POST /api/chat), que permite fijar el tamaño del
 * contexto y apagar el «razonamiento» de los modelos que piensan antes de
 * responder.
 * <p>
 * Los nombres de modelo cambian a menudo: los de aquí son solo los propuestos
 * por defecto. En la configuración se puede escribir cualquier otro o pulsar
 * «Cargar modelos» para pedir al servicio su lista actual.
 */
public final class ProveedorIA {

    /** Cómo se paga el servicio (así se agrupan en la pantalla de configuración). */
    public enum Categoria {
        GRATUITA("Gratuitas", "No cuestan nada: se ejecutan en tu ordenador o usan modelos gratuitos."),
        CON_VERSION_GRATUITA("De pago con versión gratuita",
                "Tienen un nivel gratuito con límites diarios; si se queda corto, se puede pasar al de pago."),
        DE_PAGO("De pago", "Se paga por uso (por millón de «tokens»). Suelen ser los más precisos."),
        PERSONALIZADA("Otro servicio", "Cualquier servicio compatible con la API de OpenAI (dirección propia).");

        private final String titulo;
        private final String explicacion;

        Categoria(String titulo, String explicacion) {
            this.titulo = titulo;
            this.explicacion = explicacion;
        }

        public String titulo() {
            return titulo;
        }

        public String explicacion() {
            return explicacion;
        }
    }

    /** Formato de la API. */
    public enum Protocolo {
        /** POST {base}/chat/completions (OpenAI y todos los compatibles). */
        OPENAI,
        /** POST {base}/messages (Anthropic). */
        ANTHROPIC,
        /** POST {base}/api/chat (API propia de Ollama). */
        OLLAMA
    }

    /** Cómo se pide que la respuesta sea JSON. */
    public enum FormatoJson {
        /** response_format = json_schema (salida estructurada: el modelo no puede salirse del esquema). */
        ESQUEMA,
        /** response_format = json_object («modo JSON»). */
        OBJETO,
        /** Nada: se pide en las instrucciones y se rescata el JSON de la respuesta. */
        NINGUNO
    }

    private final String id;
    private final String nombre;
    private final Categoria categoria;
    private final Protocolo protocolo;
    private final String urlBase;
    private final List<String> modelos;
    private final String urlClave;
    private final String nota;
    private final List<String> pasos;
    private final boolean necesitaClave;
    private final boolean local;
    private final String variableEntorno;
    private final String grupoClave;
    private final FormatoJson formatoJson;
    private final int limiteEntrada;
    private final int loteRecomendado;

    private ProveedorIA(Builder b) {
        this.id = b.id;
        this.nombre = b.nombre;
        this.categoria = b.categoria;
        this.protocolo = b.protocolo;
        this.urlBase = b.urlBase;
        this.modelos = Collections.unmodifiableList(Arrays.asList(b.modelos));
        this.urlClave = b.urlClave;
        this.nota = b.nota;
        this.pasos = Collections.unmodifiableList(Arrays.asList(b.pasos));
        this.necesitaClave = b.necesitaClave;
        this.local = b.local;
        this.variableEntorno = b.variableEntorno;
        this.grupoClave = b.grupoClave == null ? b.id : b.grupoClave;
        this.formatoJson = b.formatoJson;
        this.limiteEntrada = b.limiteEntrada;
        this.loteRecomendado = b.loteRecomendado;
    }

    /** Para escribir la lista de servicios de forma legible. */
    private static final class Builder {
        private final String id;
        private final String nombre;
        private final Categoria categoria;
        private Protocolo protocolo = Protocolo.OPENAI;
        private String urlBase = "";
        private String[] modelos = new String[0];
        private String urlClave = "";
        private String nota = "";
        private String[] pasos = new String[0];
        private boolean necesitaClave = true;
        private boolean local;
        private String variableEntorno;
        private String grupoClave;
        private FormatoJson formatoJson = FormatoJson.OBJETO;
        private int limiteEntrada = 12000;
        private int loteRecomendado = 8;

        Builder(String id, String nombre, Categoria categoria) {
            this.id = id;
            this.nombre = nombre;
            this.categoria = categoria;
        }

        Builder protocolo(Protocolo p) {
            protocolo = p;
            return this;
        }

        Builder url(String u) {
            urlBase = u;
            return this;
        }

        Builder modelos(String... m) {
            modelos = m;
            return this;
        }

        Builder clave(String url, String variable) {
            urlClave = url;
            variableEntorno = variable;
            return this;
        }

        Builder nota(String n) {
            nota = n;
            return this;
        }

        Builder pasos(String... p) {
            pasos = p;
            return this;
        }

        Builder sinClave() {
            necesitaClave = false;
            return this;
        }

        Builder local() {
            local = true;
            necesitaClave = false;
            return this;
        }

        Builder grupoClave(String g) {
            grupoClave = g;
            return this;
        }

        Builder json(FormatoJson f) {
            formatoJson = f;
            return this;
        }

        /** Tokens de entrada por consulta a partir de los que conviene partir el lote. */
        Builder limiteEntrada(int tokens) {
            limiteEntrada = tokens;
            return this;
        }

        Builder lote(int grupos) {
            loteRecomendado = grupos;
            return this;
        }

        ProveedorIA crear() {
            return new ProveedorIA(this);
        }
    }

    /** Todos los servicios, en el orden en que se muestran. */
    public static final List<ProveedorIA> TODOS;

    static {
        List<ProveedorIA> l = new ArrayList<>();
        // ---------------------------------------------------------------- gratuitas
        l.add(new Builder("ollama", "Ollama (en tu ordenador)", Categoria.GRATUITA)
                .protocolo(Protocolo.OLLAMA).url("http://localhost:11434").local()
                .modelos("qwen3:8b", "qwen3.5:9b", "gemma4:e4b", "llama3.1:8b", "gemma3:12b", "gpt-oss:20b", "llama3.2:3b")
                .clave("https://ollama.com/download", null)
                .json(FormatoJson.ESQUEMA).limiteEntrada(7000).lote(4)
                .nota("Gratis y sin clave. El modelo se ejecuta en tu propio ordenador: ningún dato sale de él. "
                        + "Instala Ollama, descarga un modelo (por ejemplo «ollama pull qwen3:8b») y pulsa «Cargar modelos». "
                        + "Los modelos de 8.000 millones de parámetros («8b») necesitan unos 8 GB de memoria libre; "
                        + "con poca memoria, usa «llama3.2:3b». Con tarjeta gráfica es mucho más rápido.")
                .pasos("Descarga Ollama de https://ollama.com/download e instálalo (Windows: OllamaSetup.exe; "
                                + "macOS: arrastra Ollama a Aplicaciones; Linux: curl -fsSL https://ollama.com/install.sh | sh).",
                        "Abre una terminal (en Windows, «Símbolo del sistema» o PowerShell) y descarga un modelo: "
                                + "ollama pull qwen3:8b",
                        "Deja Ollama abierto (en Windows y macOS queda en la bandeja o la barra de menús) y pulsa aquí "
                                + "«Cargar modelos». No hace falta clave.")
                .crear());
        l.add(new Builder("lmstudio", "LM Studio (en tu ordenador)", Categoria.GRATUITA)
                .url("http://localhost:1234/v1").local()
                .clave("https://lmstudio.ai", null)
                .json(FormatoJson.ESQUEMA).limiteEntrada(7000).lote(4)
                .nota("Gratis y sin clave. Como Ollama, pero con ventana propia: descarga un modelo en LM Studio, "
                        + "cárgalo con un contexto de al menos 16.384 tokens, activa su servidor local y pulsa «Cargar modelos».")
                .pasos("Descarga LM Studio de https://lmstudio.ai e instálalo.",
                        "En «Discover», descarga un modelo (por ejemplo Qwen3 8B o Llama 3.1 8B Instruct).",
                        "En la pestaña «Developer», carga el modelo con «Context Length» de 16.384 o más y activa "
                                + "«Start server» (puerto 1234).",
                        "Pulsa aquí «Cargar modelos». No hace falta clave.")
                .crear());
        l.add(new Builder("openrouter-gratis", "OpenRouter (modelos gratis)", Categoria.GRATUITA)
                .url("https://openrouter.ai/api/v1")
                .modelos("openrouter/free", "openai/gpt-oss-120b:free", "meta-llama/llama-3.3-70b-instruct:free")
                .clave("https://openrouter.ai/settings/keys", "OPENROUTER_API_KEY").grupoClave("openrouter")
                .nota("Gratis y sin tarjeta con los modelos terminados en «:free» (u «openrouter/free», que elige uno "
                        + "gratuito disponible): unas 20 consultas por minuto y 50 al día (1.000 al día si alguna vez se "
                        + "recargan 10 $). Cada consulta clasifica varios grupos.")
                .pasos("Entra en https://openrouter.ai y crea una cuenta (con Google, GitHub o correo).",
                        "Ve a «Settings → API Keys» (https://openrouter.ai/settings/keys) y pulsa «Create API Key».",
                        "Copia la clave (empieza por «sk-or-») y pégala aquí. Los modelos «:free» no gastan saldo.")
                .crear());
        // ---------------------------------------------------------------- de pago con versión gratuita
        l.add(new Builder("gemini", "Google Gemini", Categoria.CON_VERSION_GRATUITA)
                .url("https://generativelanguage.googleapis.com/v1beta/openai")
                .modelos("gemini-3.5-flash-lite", "gemini-3.8-flash", "gemini-3.1-flash-lite", "gemini-flash-latest")
                .clave("https://aistudio.google.com/apikey", "GEMINI_API_KEY")
                .json(FormatoJson.ESQUEMA)
                .nota("Clave gratuita en Google AI Studio, con límites por minuto y por día según el modelo (se ven en "
                        + "AI Studio): los «flash-lite», como el propuesto, admiten cientos de consultas gratis al día; "
                        + "«gemini-3.8-flash», más preciso, solo unas 20. En el nivel gratuito Google puede usar lo enviado "
                        + "para mejorar sus productos.")
                .pasos("Entra en https://aistudio.google.com/apikey con una cuenta de Google y acepta las condiciones.",
                        "Pulsa «Create API key» (o «Crear clave de API»); si lo pide, elige o crea un proyecto.",
                        "Copia la clave (empieza por «AIza») y pégala aquí. No hace falta tarjeta para el nivel gratuito.")
                .crear());
        l.add(new Builder("groq", "Groq", Categoria.CON_VERSION_GRATUITA)
                .url("https://api.groq.com/openai/v1")
                .modelos("llama-3.3-70b-versatile", "openai/gpt-oss-120b", "openai/gpt-oss-20b", "llama-3.1-8b-instant",
                        "qwen/qwen3.8-27b")
                .clave("https://console.groq.com/keys", "GROQ_API_KEY")
                .limiteEntrada(4500).lote(4)
                .nota("Muy rápido. Nivel gratuito permanente y sin tarjeta: unas 30 consultas por minuto y 1.000 al día, "
                        + "con un tope de tokens por minuto (por eso se le envían lotes más pequeños).")
                .pasos("Entra en https://console.groq.com y crea una cuenta (con Google, GitHub o correo).",
                        "Ve a «API Keys» (https://console.groq.com/keys) y pulsa «Create API Key».",
                        "Copia la clave (empieza por «gsk_»; solo se ve una vez) y pégala aquí.")
                .crear());
        l.add(new Builder("mistral", "Mistral AI", Categoria.CON_VERSION_GRATUITA)
                .url("https://api.mistral.ai/v1")
                .modelos("mistral-small-latest", "mistral-medium-latest", "mistral-large-latest")
                .clave("https://console.mistral.ai/api-keys", "MISTRAL_API_KEY")
                .nota("Empresa europea. Modo gratuito sin tarjeta, con límites de uso (y con permiso para usar los datos "
                        + "para mejorar sus modelos); los planes de pago no tienen esa condición.")
                .pasos("Entra en https://console.mistral.ai y crea una cuenta.",
                        "En el menú de la izquierda elige «API Keys» y pulsa «Create new key».",
                        "Copia la clave (solo se ve una vez) y pégala aquí.")
                .crear());
        l.add(new Builder("cerebras", "Cerebras", Categoria.CON_VERSION_GRATUITA)
                .url("https://api.cerebras.ai/v1")
                .modelos("gpt-oss-120b", "qwen-3.8-27b")
                .clave("https://cloud.cerebras.ai", "CEREBRAS_API_KEY")
                .nota("Muy rápido. Cuenta gratuita con límites por minuto y por día más bajos que los de pago.")
                .pasos("Entra en https://cloud.cerebras.ai y crea una cuenta.",
                        "En el menú de la izquierda elige «API Keys» y crea una clave.",
                        "Copia la clave (empieza por «csk-») y pégala aquí.")
                .crear());
        l.add(new Builder("openrouter", "OpenRouter", Categoria.CON_VERSION_GRATUITA)
                .url("https://openrouter.ai/api/v1")
                .modelos("openai/gpt-oss-120b", "meta-llama/llama-3.3-70b-instruct")
                .clave("https://openrouter.ai/settings/keys", "OPENROUTER_API_KEY")
                .nota("Una sola clave para cientos de modelos de distintas empresas; se paga con saldo prepago. "
                        + "Los modelos «:free» no gastan saldo.")
                .pasos("Entra en https://openrouter.ai y crea una cuenta.",
                        "Añade saldo en «Settings → Credits» si vas a usar modelos de pago.",
                        "En «Settings → API Keys» pulsa «Create API Key», copia la clave (empieza por «sk-or-») y pégala aquí.")
                .crear());
        l.add(new Builder("cohere", "Cohere", Categoria.CON_VERSION_GRATUITA)
                .url("https://api.cohere.ai/compatibility/v1")
                .modelos("command-a-plus-05-2026")
                .clave("https://dashboard.cohere.com/api-keys", "CO_API_KEY")
                .nota("Clave de prueba («trial») gratuita, para evaluar: 1.000 consultas al mes y 20 por minuto. "
                        + "La clave de producción es de pago.")
                .pasos("Entra en https://dashboard.cohere.com y crea una cuenta.",
                        "Ve a «API Keys» (https://dashboard.cohere.com/api-keys): ya hay una «Trial key» creada.",
                        "Cópiala y pégala aquí.")
                .crear());
        // ---------------------------------------------------------------- de pago
        l.add(new Builder("openai", "OpenAI (ChatGPT)", Categoria.DE_PAGO)
                .url("https://api.openai.com/v1")
                .modelos("gpt-6-luna", "gpt-6.1-sol", "gpt-6-astra")
                .clave("https://platform.openai.com/api-keys", "OPENAI_API_KEY")
                .json(FormatoJson.ESQUEMA)
                .nota("De pago por uso (la suscripción a ChatGPT no sirve: la API se paga aparte). «gpt-6-luna» es el más "
                        + "económico (unos 0,10 $ por millón de tokens de entrada) y basta para catalogar.")
                .pasos("Entra en https://platform.openai.com con tu cuenta de OpenAI (la misma de ChatGPT sirve).",
                        "Añade saldo en «Settings → Billing» (la API no es gratuita).",
                        "Ve a https://platform.openai.com/api-keys y pulsa «Create new secret key».",
                        "Copia la clave (empieza por «sk-»; solo se ve una vez) y pégala aquí.")
                .crear());
        l.add(new Builder("anthropic", "Anthropic (Claude)", Categoria.DE_PAGO)
                .protocolo(Protocolo.ANTHROPIC).url("https://api.anthropic.com/v1")
                .modelos("claude-haiku-5-5", "claude-sonnet-5-5", "claude-opus-5-5", "claude-fable-5-1")
                .clave("https://platform.claude.com/settings/keys", "ANTHROPIC_API_KEY")
                .json(FormatoJson.NINGUNO)
                .nota("De pago por uso (la suscripción a Claude no sirve: la API se paga aparte). «claude-haiku-5-5» es "
                        + "el más económico; «claude-sonnet-5-5» y «claude-opus-5-5» razonan mejor los casos difíciles.")
                .pasos("Entra en la consola de Claude, https://platform.claude.com, y crea una cuenta.",
                        "Compra crédito en «Settings → Billing».",
                        "Ve a «API keys» (https://platform.claude.com/settings/keys), pulsa «Create key», ponle un nombre "
                                + "y elige cuándo caduca.",
                        "Copia la clave (empieza por «sk-ant-»; solo se ve una vez) y pégala aquí.")
                .crear());
        l.add(new Builder("deepseek", "DeepSeek", Categoria.DE_PAGO)
                .url("https://api.deepseek.com")
                .modelos("deepseek-flash", "deepseek-v4-pro")
                .clave("https://platform.deepseek.com/api_keys", "DEEPSEEK_API_KEY")
                .nota("De pago por uso, con precios muy bajos. Los servidores están en China.")
                .pasos("Entra en https://platform.deepseek.com y crea una cuenta.",
                        "Recarga saldo en «Top up» (sin saldo responde «Insufficient Balance»).",
                        "Ve a «API keys», pulsa «Create new API key», copia la clave (empieza por «sk-») y pégala aquí.")
                .crear());
        l.add(new Builder("xai", "xAI (Grok)", Categoria.DE_PAGO)
                .url("https://api.x.ai/v1")
                .modelos("grok-4.7")
                .clave("https://console.x.ai", "XAI_API_KEY")
                .nota("De pago por uso.")
                .pasos("Entra en https://console.x.ai y crea una cuenta.",
                        "Carga crédito en la cuenta.",
                        "En «API Keys» crea una clave, cópiala (empieza por «xai-») y pégala aquí.")
                .crear());
        l.add(new Builder("huggingface", "Hugging Face", Categoria.DE_PAGO)
                .url("https://router.huggingface.co/v1")
                .modelos("openai/gpt-oss-120b", "meta-llama/Llama-3.3-70B-Instruct", "Qwen/Qwen3-32B")
                .clave("https://huggingface.co/settings/tokens", "HF_TOKEN")
                .nota("Modelos abiertos servidos por varios proveedores. Las cuentas PRO incluyen 2 $ de consultas al mes; "
                        + "las gratuitas necesitan comprar crédito. El token necesita el permiso «Make calls to Inference Providers».")
                .pasos("Entra en https://huggingface.co y crea una cuenta.",
                        "Ve a https://huggingface.co/settings/tokens y pulsa «Create new token» (tipo «Fine-grained»).",
                        "Marca el permiso «Make calls to Inference Providers» y crea el token.",
                        "Copia el token (empieza por «hf_») y pégalo aquí.")
                .crear());
        // ---------------------------------------------------------------- personalizada
        l.add(new Builder("personalizado", "Otro servicio compatible con OpenAI", Categoria.PERSONALIZADA)
                .url("https://").sinClave()
                .nota("Escribe la dirección base de la API (la que termina en /v1), la clave si la pide y el modelo. "
                        + "Sirve para servidores propios del hospital (vLLM, LocalAI…) o para Azure OpenAI.")
                .pasos("Pide al servicio de informática la dirección de la API (termina en /v1), el nombre del modelo y, "
                        + "si la necesita, una clave.")
                .crear());
        TODOS = Collections.unmodifiableList(l);
    }

    public static ProveedorIA porId(String id) {
        for (ProveedorIA p : TODOS) {
            if (p.id.equals(id)) {
                return p;
            }
        }
        return null;
    }

    public static List<ProveedorIA> deCategoria(Categoria c) {
        List<ProveedorIA> l = new ArrayList<>();
        for (ProveedorIA p : TODOS) {
            if (p.categoria == c) {
                l.add(p);
            }
        }
        return l;
    }

    public String getId() {
        return id;
    }

    public String getNombre() {
        return nombre;
    }

    /** Nombre sin aclaraciones, para la cabecera: «Groq», «Ollama», «Servicio propio». */
    public String getNombreCorto() {
        return categoria == Categoria.PERSONALIZADA ? "Servicio propio" : nombre.replaceAll(" \\(.*\\)$", "");
    }

    public Categoria getCategoria() {
        return categoria;
    }

    public Protocolo getProtocolo() {
        return protocolo;
    }

    public String getUrlBase() {
        return urlBase;
    }

    public List<String> getModelos() {
        return modelos;
    }

    public String getModeloPorDefecto() {
        return modelos.isEmpty() ? "" : modelos.get(0);
    }

    /** Página donde se consigue la clave (o se descarga el programa, si es local). */
    public String getUrlClave() {
        return urlClave;
    }

    public String getNota() {
        return nota;
    }

    /** Pasos para conseguir la clave (o para instalar el programa local). */
    public List<String> getPasos() {
        return pasos;
    }

    public boolean necesitaClave() {
        return necesitaClave;
    }

    /** Se ejecuta en el propio ordenador: los datos no salen de él. */
    public boolean isLocal() {
        return local;
    }

    /** Variable de entorno de la que se toma la clave si no se ha escrito ninguna. */
    public String getVariableEntorno() {
        return variableEntorno;
    }

    /** Proveedores que comparten la misma clave (OpenRouter gratis y de pago). */
    public String getGrupoClave() {
        return grupoClave;
    }

    public FormatoJson getFormatoJson() {
        return formatoJson;
    }

    /** Tokens de entrada aproximados por consulta a partir de los que se parte el lote. */
    public int getLimiteEntrada() {
        return limiteEntrada;
    }

    /** Grupos por consulta que mejor funcionan con este servicio. */
    public int getLoteRecomendado() {
        return loteRecomendado;
    }

    /** La dirección se puede cambiar (servicios locales o personalizados). */
    public boolean urlEditable() {
        return local || categoria == Categoria.PERSONALIZADA;
    }

    @Override
    public String toString() {
        return nombre;
    }
}
