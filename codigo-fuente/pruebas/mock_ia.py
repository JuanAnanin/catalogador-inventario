# Servidor de IA simulado para probar el Catalogador sin gastar consultas reales.
#
# La "inteligencia" es un emparejador de palabras: suficiente para comprobar el circuito
# completo. Imita las particularidades documentadas de cada servicio según el principio
# de la dirección:
#
#   /v1/...                     genérico compatible con OpenAI (comportamientos según la clave)
#   /openai/v1/...              OpenAI: los gpt-6 rechazan temperature y max_tokens (piden
#                               max_completion_tokens); salida estructurada json_schema con
#                               strict; sin saldo -> 429 insufficient_quota
#   /gemini/v1beta/openai/...   Gemini: errores dentro de una lista; clave mala -> 400
#                               API_KEY_INVALID; cuota diaria con retryDelay; límite por minuto
#                               con retryDelay corto; ids «models/…»; rechaza response_format
#                               json_object y reasoning_effort «none» en los gemini-3
#   /anthropic/v1/...           Anthropic: exige x-api-key, anthropic-version y max_tokens;
#                               bloques thinking + text; sin saldo -> 400 credit balance;
#                               529 sobrecarga; output_config.effort
#   /groq/openai/v1/...         Groq: json_validate_failed; 413 si la consulta supera los TPM;
#                               reasoning_effort solo en gpt-oss y qwen; límite por minuto con
#                               «Please try again in 1.5s»
#   /mistral/v1/...             Mistral: errores {"message": …}; reasoning_effort none|high
#   /deepseek/...               DeepSeek: sin «thinking: disabled» responde con razonamiento;
#                               a veces respuesta vacía en modo JSON
#   /openrouter/api/v1/...      OpenRouter: objeto «reasoning»; cuota diaria free-models-per-day
#   /lmstudio/v1/...            LM Studio: solo admite response_format json_schema
#   /cohere/compatibility/v1/   Cohere: la API compatible no da lista de modelos (404); la
#                               propia (/cohere/v1/models) sí
#   /ollama/api/...             Ollama (API propia): tags, show (capacidades, familia, plantilla
#                               y contexto, como el Ollama real), chat (format con esquema, think,
#                               num_ctx; gpt-oss con think=false responde vacío). Si num_ctx
#                               no basta, RECORTA la consulta como el Ollama real (se pierden
#                               las instrucciones y la respuesta no sirve). Modelo «gigante:70b»:
#                               no cabe en memoria.
#
# Claves especiales (en cualquier servicio):
#   mal -> 401 · sinsaldo -> 402 · cuota -> cuota diaria · lento429 -> 2 × 429 con espera corta
#   fallo500 -> 1 de cada 3 consultas 500 · nojson -> 400 si se pide response_format y
#   responde con ```json … ``` y texto alrededor · sobrecarga -> 1 × 529 · piensa -> <think>
#   sinsystem -> rechaza el rol «system» · vacia -> la primera respuesta llega vacía
#   jsonmal (Groq) -> json_validate_failed la 1.ª vez · tpm (Groq) -> 413 si es grande
#   corta -> con más de 2 grupos, la respuesta se corta (finish_reason «length»)
#
# Uso: python3 mock_ia.py [puerto] [fichero_log] [retardo_segundos]
import json, re, sys, time, unicodedata, threading
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

LOG = open(sys.argv[2] if len(sys.argv) > 2 else 'mock_ia.log', 'a', encoding='utf-8')
contador = {}
RETARDO = float(sys.argv[3]) if len(sys.argv) > 3 else 0.05
lock = threading.Lock()

SINONIMOS = {'a/a': 'aire acondicionado', 'met.': 'metalico', 'conf.': 'confidente', 'acomp.': 'acompanante',
             'portasueros': 'pie gotero', 'aux.': 'auxiliar', 'mesita': 'mesa', 'arcon': 'arcon congelador'}
VACIAS = {'de', 'del', 'la', 'el', 'los', 'las', 'y', 'con', 'para', 'por', 'en', 'a', 'uso', 'general'}

# Modelos de Ollama simulados como los devuelve /api/show del Ollama real (0.31): capacidades, familia,
# contexto y plantilla. El Ollama real NO dice qué valores de «think» admite cada modelo: se ve en la
# plantilla. «think»: se apaga con think=false; «niveles» (gpt-oss): «Reasoning: low/medium/high», con
# think=false responde vacío; «siempre»: piensa siempre y no lo dice (con think=false responde vacío).
PLANTILLAS = {
    'think': '{{- if and .IsThinkSet (not .Think) }} /no_think{{ end }}{{ .Prompt }}',
    'niveles': '<|start|>system<|message|>{{- if and .IsThinkSet .Think (ne .ThinkLevel "") }}\n\nReasoning: {{ .ThinkLevel }}'
               '\n{{- else if or (not .IsThinkSet) (and .IsThinkSet .Think) }}\n\nReasoning: medium\n{{- end }}{{ .Prompt }}',
    'siempre': '{{ .System }}{{ .Prompt }}',
    'normal': '{{ .System }}{{ .Prompt }}',
}
OLLAMA = {
    'qwen3:8b': {'capabilities': ['completion', 'tools', 'thinking'], 'plantilla': 'think'},
    'qwen3.5:9b': {'capabilities': ['completion', 'vision', 'tools', 'thinking'], 'plantilla': 'think'},
    'gemma3:12b': {'capabilities': ['completion', 'vision']},
    'gemma4:e4b': {'capabilities': ['completion', 'vision', 'audio', 'tools', 'thinking'], 'plantilla': 'think'},
    'gpt-oss:20b': {'capabilities': ['completion', 'tools', 'thinking'], 'plantilla': 'niveles', 'familia': 'gpt-oss'},
    'deepseek-r1:8b': {'capabilities': ['completion', 'tools', 'thinking'], 'plantilla': 'think', 'texto': True},
    'llama3.1:8b': {'capabilities': ['completion', 'tools']},
    'llama3.2:3b': {'capabilities': ['completion', 'tools']},
    'phi3:mini': {'capabilities': ['completion'], 'contexto': 4096, 'prueba': True},
    'mistral:latest': {'capabilities': ['completion', 'tools']},
    'razonador:7b': {'capabilities': ['completion', 'thinking'], 'plantilla': 'siempre', 'prueba': True},
    'gigante:70b': {'capabilities': ['completion'], 'memoria': True, 'prueba': True},
    'nomic-embed-text:latest': {'capabilities': ['embedding'], 'familia': 'nomic-bert'},
}
CONTEXTO_POR_DEFECTO_OLLAMA = 4096


def norm(s):
    s = s.lower()
    for k, v in SINONIMOS.items():
        s = s.replace(k, ' ' + v + ' ')
    s = unicodedata.normalize('NFD', s)
    s = ''.join(c for c in s if unicodedata.category(c) != 'Mn')
    pal = [p for p in re.split(r'[^a-z0-9]+', s) if p and p not in VACIAS]
    return {p[:-1] if len(p) > 4 and p.endswith('s') else p for p in pal}


def elegir(eq):
    nom = norm(eq['nombre'] + ' ' + ' '.join(eq.get('otras_formas_de_escribirlo', [])))
    mejor, puntos = None, 0.0
    electro = eq.get('tipo_activo') == 'E'
    for c in eq.get('candidatos', []):
        if electro and c.get('catalogo') != 'GMDN':
            continue   # la electromedicina va al catálogo GMDN
        cn = norm(c['nombre'])
        if not cn:
            continue
        comun = len(nom & cn)
        p = comun / len(cn | nom) if comun else 0
        if comun == len(cn):
            p += 0.5   # todas las palabras del término están en el nombre
        if p > puntos:
            mejor, puntos = c, p
    if mejor is None or puntos < 0.2:
        buscar = ['pie de gotero', 'portasueros'] if 'gotero' in nom or 'portasuero' in nom else [eq['nombre'].split()[0].lower()]
        motivo = ('Es un equipo de electromedicina y ningún término GMDN candidato es ese aparato.' if electro
                  else 'Ningún candidato es el mismo tipo de aparato.')
        return {'id': eq['id'], 'eleccion': '', 'confianza': 0, 'motivo': motivo, 'buscar': buscar,
                'marca': '', 'modelo': ''}
    conf = int(min(98, 55 + 30 * puntos))
    r = {'id': eq['id'], 'eleccion': mejor['id'], 'confianza': conf,
         'motivo': f'«{eq["nombre"]}» corresponde a «{mejor["nombre"]}».', 'buscar': [], 'marca': '', 'modelo': ''}
    marca = (eq.get('marca') or '').strip()
    arreglos = {'BRAUN': 'B. BRAUN', 'B BRAUN': 'B. BRAUN', 'FRESENIUS': 'FRESENIUS KABI',
                'MITSUBISHI': 'MITSUBISHI ELECTRIC'}
    if marca.upper() in arreglos:
        r['marca'] = arreglos[marca.upper()]
    return r


def responder_items(texto_usuario):
    # El JSON de los datos va al final del mensaje (puede llevar delante las instrucciones,
    # que también contienen llaves, si el servicio no admite el rol «system»).
    dec = json.JSONDecoder()
    datos = None
    for i, c in enumerate(texto_usuario):
        if c != '{':
            continue
        try:
            v, fin = dec.raw_decode(texto_usuario, i)
        except ValueError:
            continue
        if isinstance(v, dict) and ('equipos' in v or 'ok' in v):
            datos = v
            if 'equipos' in v:
                break
    if datos is None or 'equipos' not in datos:
        return {'ok': True}   # prueba de conexión
    return {'resultados': [elegir(e) for e in datos['equipos']]}


class H(BaseHTTPRequestHandler):
    protocol_version = 'HTTP/1.1'

    def log_message(self, *a):
        pass

    def enviar(self, estado, obj, cabeceras=None):
        cuerpo = json.dumps(obj, ensure_ascii=False).encode('utf-8')
        self.send_response(estado)
        self.send_header('Content-Type', 'application/json')
        self.send_header('Content-Length', str(len(cuerpo)))
        for k, v in (cabeceras or {}).items():
            self.send_header(k, v)
        self.end_headers()
        self.wfile.write(cuerpo)

    def clave(self):
        a = self.headers.get('Authorization', '')
        return a[7:] if a.startswith('Bearer ') else self.headers.get('x-api-key', '')

    def perfil(self):
        for p in ('openai', 'gemini', 'anthropic', 'groq', 'mistral', 'deepseek', 'openrouter', 'lmstudio', 'cohere', 'ollama',
                  'cerebras', 'xai', 'huggingface'):
            if self.path.startswith('/' + p + '/'):
                return p
        if self.path.startswith('/api/'):
            return 'ollama'   # servidor simulado en el puerto de Ollama (http://localhost:11434)
        return 'generico'

    def clave_mala(self, perfil):
        if perfil == 'gemini':
            return self.enviar(400, [{'error': {'code': 400, 'message': 'API key not valid. Please pass a valid API key.',
                                                'status': 'INVALID_ARGUMENT',
                                                'details': [{'@type': 'type.googleapis.com/google.rpc.ErrorInfo', 'reason': 'API_KEY_INVALID'}]}}])
        if perfil == 'anthropic':
            return self.enviar(401, {'type': 'error', 'error': {'type': 'authentication_error', 'message': 'invalid x-api-key'}})
        if perfil == 'mistral':
            return self.enviar(401, {'message': 'Unauthorized', 'request_id': 'abc'})
        return self.enviar(401, {'error': {'message': 'Incorrect API key provided', 'type': 'invalid_request_error',
                                           'code': 'invalid_api_key'}})

    # ------------------------------------------------------------------ GET
    def do_GET(self):
        perfil = self.perfil()
        k = self.clave()
        ruta = self.path.split('?')[0]
        if perfil == 'ollama':
            if ruta.endswith('/api/tags'):
                # En el puerto de Ollama (sin prefijo «/ollama») no se listan los modelos que solo sirven para las pruebas
                reales = ruta.startswith('/api/')
                return self.enviar(200, {'models': [{'name': n, 'model': n, 'size': 5000000000,
                                                     'details': {'format': 'gguf', 'family': d.get('familia', n.split(':')[0])}}
                                                    for n, d in OLLAMA.items() if not (reales and d.get('prueba'))]})
            return self.enviar(404, {'error': 'not found'})
        if perfil == 'cohere':
            if ruta.endswith('/compatibility/v1/models'):
                return self.enviar(404, {'message': 'Not Found'})
            if ruta.endswith('/cohere/v1/models'):
                if k == 'mal':
                    return self.enviar(401, {'message': 'invalid api token'})
                return self.enviar(200, {'models': [{'name': 'command-a-plus-05-2026', 'endpoints': ['chat']},
                                                    {'name': 'embed-v5.0-pro', 'endpoints': ['embed']}]})
        if not ruta.endswith('/models'):
            return self.enviar(404, {'error': {'message': 'not found'}})
        if k == 'mal':
            return self.clave_mala(perfil)
        if perfil == 'anthropic' and self.headers.get('anthropic-version') != '2023-06-01':
            return self.enviar(400, {'type': 'error', 'error': {'type': 'invalid_request_error', 'message': 'anthropic-version required'}})
        if perfil == 'gemini':
            ids = ['models/gemini-3.8-flash', 'models/gemini-3.1-flash-lite', 'models/gemini-embedding-001',
                   'models/veo-3.1-generate-preview', 'models/gemini-3.8-flash-tts']
        elif perfil == 'anthropic':
            ids = ['claude-haiku-5-5', 'claude-sonnet-5-5', 'claude-opus-5-5', 'claude-fable-5-1']
        elif perfil == 'openai':
            ids = ['gpt-6-luna', 'gpt-6.1-sol', 'gpt-6-astra', 'text-embedding-3-small', 'gpt-realtime-2', 'gpt-image-2', 'whisper-1']
        elif perfil == 'groq':
            ids = ['openai/gpt-oss-120b', 'llama-3.3-70b-versatile', 'whisper-large-v3', 'meta-llama/llama-prompt-guard-2-86m']
        else:
            ids = ['modelo-simulado-grande', 'modelo-simulado', 'text-embedding-3-small']
        self.enviar(200, {'object': 'list', 'data': [{'id': i} for i in ids]})

    # ------------------------------------------------------------------ POST
    def do_POST(self):
        n = int(self.headers.get('Content-Length', '0'))
        req = json.loads(self.rfile.read(n).decode('utf-8'))
        k = self.clave()
        perfil = self.perfil()
        with lock:
            contador[perfil + k] = contador.get(perfil + k, 0) + 1
            num = contador[perfil + k]
        rf = req.get('response_format')
        LOG.write(json.dumps({'t': time.time(), 'ruta': self.path, 'perfil': perfil, 'clave': k, 'n': num,
                              'modelo': req.get('model'),
                              'response_format': (rf or {}).get('type') if isinstance(rf, dict) else rf,
                              'strict': ((rf or {}).get('json_schema') or {}).get('strict') if isinstance(rf, dict) else None,
                              'format': ('esquema' if isinstance(req.get('format'), dict) else req.get('format')),
                              'roles': [m.get('role') for m in req.get('messages', [])],
                              'extras': {x: req[x] for x in req if x in ('reasoning_effort', 'reasoning', 'thinking', 'temperature',
                                                                         'think', 'output_config', 'max_tokens',
                                                                         'max_completion_tokens', 'include_reasoning',
                                                                         'keep_alive', 'stream')},
                              'options': req.get('options'),
                              'cabeceras': {'anthropic-version': self.headers.get('anthropic-version'),
                                            'x-openrouter-title': self.headers.get('X-OpenRouter-Title')},
                              'bytes': n}, ensure_ascii=False) + '\n')
        LOG.flush()
        if perfil == 'ollama':
            return self.ollama(req)
        # ---- errores comunes según la clave
        if k == 'mal':
            return self.clave_mala(perfil)
        if k == 'sinsaldo':
            if perfil == 'openai':
                return self.enviar(429, {'error': {'message': 'You exceeded your current quota, please check your plan and billing details.',
                                                   'type': 'insufficient_quota', 'param': None, 'code': 'insufficient_quota'}})
            if perfil == 'anthropic':
                return self.enviar(400, {'type': 'error', 'error': {'type': 'invalid_request_error',
                                                                     'message': 'Your credit balance is too low to access the Anthropic API. Please go to Plans & Billing to upgrade or purchase credits.'}})
            if perfil == 'deepseek':
                return self.enviar(402, {'error': {'message': 'Insufficient Balance', 'type': 'unknown_error'}})
            return self.enviar(402, {'error': {'message': 'Insufficient credits', 'code': 402}})
        if k == 'cuota':
            if perfil == 'gemini':
                return self.enviar(429, [{'error': {'code': 429, 'status': 'RESOURCE_EXHAUSTED',
                                                    'message': 'You exceeded your current quota, please check your plan and billing details. Quota exceeded for metric: generativelanguage.googleapis.com/generate_content_free_tier_requests, limit: 250, model: gemini-3.8-flash. Please retry in 14h3m5.2s.',
                                                    'details': [{'@type': 'type.googleapis.com/google.rpc.QuotaFailure',
                                                                 'violations': [{'quotaId': 'GenerateRequestsPerDayPerProjectPerModel-FreeTier'}]},
                                                                {'@type': 'type.googleapis.com/google.rpc.RetryInfo', 'retryDelay': '50585s'}]}}])
            if perfil in ('openrouter',):
                return self.enviar(429, {'error': {'message': 'Rate limit exceeded: free-models-per-day. Add 10 credits to unlock 1000 free model requests per day', 'code': 429}})
            return self.enviar(429, {'error': {'message': 'Rate limit reached for model on requests per day (RPD): Limit 1000, Used 1000', 'type': 'requests', 'code': 'rate_limit_exceeded'}})
        if k == 'lento429' and num <= 2:
            if perfil == 'gemini':
                return self.enviar(429, [{'error': {'code': 429, 'status': 'RESOURCE_EXHAUSTED',
                                                    'message': 'Quota exceeded for metric: generate_content_free_tier_requests, limit: 10. Please retry in 0.8s.',
                                                    'details': [{'@type': 'type.googleapis.com/google.rpc.RetryInfo', 'retryDelay': '1s'}]}}])
            if perfil == 'groq':
                return self.enviar(429, {'error': {'message': 'Rate limit reached for model `llama-3.3-70b-versatile` on tokens per minute (TPM): Limit 12000, Used 11000, Requested 2000. Please try again in 1.5s.',
                                                   'type': 'tokens', 'code': 'rate_limit_exceeded'}})
            return self.enviar(429, {'error': {'message': 'Rate limit reached on requests per minute (RPM)'}}, {'Retry-After': '1'})
        if k == 'sobrecarga' and num <= 1:
            return self.enviar(529, {'type': 'error', 'error': {'type': 'overloaded_error', 'message': 'Overloaded'}})
        if k == 'fallo500' and num % 3 == 1:
            return self.enviar(500, {'error': {'message': 'Internal server error'}})
        if k == 'nojson' and req.get('response_format'):
            return self.enviar(400, {'error': {'message': "Invalid parameter: 'response_format' of type 'json_object' is not supported with this model.", 'type': 'invalid_request_error'}})
        if k == 'sinsystem' and any(m.get('role') == 'system' for m in req.get('messages', [])):
            return self.enviar(400, {'error': {'message': 'System role not supported', 'type': 'invalid_request_error'}})
        modelo = req.get('model') or ''
        if modelo == 'no-existe':
            if perfil == 'anthropic':
                return self.enviar(404, {'type': 'error', 'error': {'type': 'not_found_error', 'message': 'model: no-existe'}})
            return self.enviar(404, {'error': {'message': 'The model `no-existe` does not exist', 'code': 'model_not_found'}})
        # ---- particularidades de cada servicio
        rf = req.get('response_format') or {}
        if perfil == 'openai':
            if 'temperature' in req and modelo.startswith('gpt-6'):
                return self.enviar(400, {'error': {'message': "Unsupported value: 'temperature' does not support 0 with this model. Only the default (1) value is supported.",
                                                   'type': 'invalid_request_error', 'param': 'temperature', 'code': 'unsupported_value'}})
            if 'max_tokens' in req:
                return self.enviar(400, {'error': {'message': "Unsupported parameter: 'max_tokens' is not supported with this model. Use 'max_completion_tokens' instead.",
                                                   'type': 'invalid_request_error', 'param': 'max_tokens', 'code': 'unsupported_parameter'}})
            esfuerzo = req.get('reasoning_effort')
            validos = ('none', 'low', 'medium', 'high', 'xhigh', 'max') if modelo == 'gpt-6-luna' else ('low', 'medium', 'high', 'xhigh', 'max')
            if esfuerzo is not None and esfuerzo not in validos:
                return self.enviar(400, {'error': {'message': f"Unsupported value: 'reasoning_effort' does not support '{esfuerzo}' with this model.",
                                                   'param': 'reasoning_effort', 'code': 'unsupported_value'}})
            if rf.get('type') == 'json_schema' and not rf.get('json_schema', {}).get('strict'):
                return self.enviar(400, {'error': {'message': 'strict must be true', 'param': 'response_format'}})
        if perfil == 'gemini':
            if rf.get('type') == 'json_object':
                return self.enviar(400, [{'error': {'code': 400, 'status': 'INVALID_ARGUMENT',
                                                    'message': 'Invalid JSON payload received. Unknown name "response_format": json_object not supported.'}}])
            if req.get('reasoning_effort') == 'none' and modelo.startswith('gemini-3'):
                return self.enviar(400, [{'error': {'code': 400, 'status': 'INVALID_ARGUMENT',
                                                    'message': 'Reasoning cannot be turned off for this model (reasoning_effort).'}}])
        if perfil == 'anthropic':
            if self.headers.get('anthropic-version') != '2023-06-01' or not self.headers.get('x-api-key'):
                return self.enviar(400, {'type': 'error', 'error': {'type': 'invalid_request_error', 'message': 'bad request'}})
            if 'max_tokens' not in req:
                return self.enviar(400, {'type': 'error', 'error': {'type': 'invalid_request_error', 'message': 'max_tokens: Field required'}})
            if 'response_format' in req or 'temperature' in req and modelo in ('claude-opus-5-5', 'claude-fable-5-1'):
                return self.enviar(400, {'type': 'error', 'error': {'type': 'invalid_request_error', 'message': 'unexpected parameter'}})
            oc = req.get('output_config') or {}
            if oc and oc.get('effort') not in ('low', 'medium', 'high', 'xhigh', 'max'):
                return self.enviar(400, {'type': 'error', 'error': {'type': 'invalid_request_error', 'message': 'output_config.effort: invalid'}})
            if oc and modelo == 'claude-antiguo':
                return self.enviar(400, {'type': 'error', 'error': {'type': 'invalid_request_error', 'message': 'output_config: Extra inputs are not permitted'}})
        if perfil == 'groq':
            if 'reasoning_effort' in req and 'gpt-oss' not in modelo and 'qwen' not in modelo:
                return self.enviar(400, {'error': {'message': '`reasoning_effort` is not supported with this model',
                                                   'type': 'invalid_request_error', 'param': 'reasoning_effort'}})
            if 'include_reasoning' in req and 'gpt-oss' not in modelo:
                return self.enviar(400, {'error': {'message': '`include_reasoning` is only supported for reasoning models',
                                                   'type': 'invalid_request_error'}})
            if k == 'jsonmal' and num == 1:
                return self.enviar(400, {'error': {'message': "Failed to generate JSON. Please adjust your prompt. See 'failed_generation' for more details.",
                                                   'type': 'invalid_request_error', 'code': 'json_validate_failed',
                                                   'failed_generation': '{"resultados": [{"id": "g1", "eleccion": "c1", "motivo": "temperature'}})
            pedidos = n // 3 + int(req.get('max_completion_tokens') or req.get('max_tokens') or 0)
            if k == 'tpm' and pedidos > 4000:
                return self.enviar(413, {'error': {'message': f'Request too large for model `{modelo}` in organization `org_x` service tier `on_demand` on tokens per minute (TPM): Limit 4000, Requested {pedidos}, please reduce your message size and try again.',
                                                   'type': 'tokens', 'code': 'rate_limit_exceeded'}})
        if perfil == 'mistral':
            if req.get('reasoning_effort') not in (None, 'none', 'high'):
                return self.enviar(422, {'object': 'error', 'message': 'reasoning_effort: Input should be \'none\' or \'high\'', 'type': 'invalid_request_error'})
        if perfil == 'lmstudio' and rf.get('type') == 'json_object':
            return self.enviar(400, {'error': "'response_format.type' must be 'json_schema' or 'text'"})
        if perfil == 'cohere' and req.get('reasoning_effort') not in (None, 'none', 'high'):
            return self.enviar(400, {'message': 'reasoning_effort must be one of: none, high'})
        # ---- respuesta
        if perfil == 'anthropic':
            usuario = req['messages'][0]['content']
        else:
            usuario = [m for m in req['messages'] if m['role'] == 'user'][0]['content']
        time.sleep(RETARDO)
        texto = json.dumps(responder_items(usuario), ensure_ascii=False)
        if k == 'nojson':
            texto = 'Claro, aquí tienes la clasificación:\n```json\n' + texto + '\n```\nEspero que te sirva.'
        if k == 'vacia' and num == 1:
            texto = ''
        if perfil == 'anthropic':
            return self.enviar(200, {'id': 'msg_1', 'type': 'message', 'role': 'assistant', 'model': modelo,
                                     'content': [{'type': 'thinking', 'thinking': 'Comparo cada grupo con sus candidatos…', 'signature': 'x'},
                                                 {'type': 'text', 'text': texto}],
                                     'stop_reason': 'end_turn',
                                     'usage': {'input_tokens': n // 4, 'output_tokens': len(texto) // 4}})
        mensaje = {'role': 'assistant', 'content': texto}
        if perfil == 'deepseek' and (req.get('thinking') or {}).get('type') != 'disabled':
            mensaje['reasoning_content'] = 'Pienso mucho antes de responder…' * 50
        if perfil == 'mistral' and req.get('reasoning_effort') == 'high':
            mensaje['content'] = [{'type': 'thinking', 'thinking': [{'type': 'text', 'text': 'Pienso… {"no": 1}'}]},
                                  {'type': 'text', 'text': texto}]
        if perfil == 'generico' and k == 'piensa':
            mensaje['content'] = '<think>Primero miro los candidatos {c1}…</think>\n' + texto
        fin = 'stop'
        if k == 'corta' and texto.count('"id"') > 2:
            mensaje['content'] = texto[:len(texto) // 2]   # se acaba el límite de tokens de salida
            fin = 'length'
        self.enviar(200, {'id': 'c1', 'object': 'chat.completion', 'model': modelo,
                          'choices': [{'index': 0, 'message': mensaje, 'finish_reason': fin}],
                          'usage': {'prompt_tokens': n // 4, 'completion_tokens': len(texto) // 4}})

    # ------------------------------------------------------------------ Ollama (API propia)
    def ollama(self, req):
        modelo = req.get('model', '') or req.get('name', '')
        if ':' not in modelo:
            modelo += ':latest'
        info = OLLAMA.get(modelo)
        if self.path.endswith('/api/show'):
            if info is None:
                return self.enviar(404, {'error': f"model '{modelo}' not found"})
            familia = info.get('familia', modelo.split(':')[0])
            r = {'capabilities': info['capabilities'], 'details': {'family': familia, 'format': 'gguf'},
                 'template': PLANTILLAS[info.get('plantilla', 'normal')],
                 'model_info': {'general.architecture': familia,
                                familia.replace('.', '') + '.context_length': info.get('contexto', 131072)}}
            return self.enviar(200, r)
        if not self.path.endswith('/api/chat'):
            return self.enviar(404, {'error': 'not found'})
        if info is None:
            return self.enviar(404, {'error': f"model '{modelo}' not found"})
        if info.get('memoria'):
            return self.enviar(500, {'error': 'model requires more system memory (41.2 GiB) than is available (11.8 GiB)'})
        if 'completion' not in info['capabilities']:
            return self.enviar(400, {'error': f'"{modelo}" does not support chat'})
        think = req.get('think', None)
        piensa = 'thinking' in info['capabilities']
        if think is not None and not piensa and think is not False:
            return self.enviar(400, {'error': f'"{modelo}" does not support thinking'})
        plantilla = info.get('plantilla', 'normal')
        if think is not None and think not in (True, False, 'low', 'medium', 'high', 'max'):
            return self.enviar(400, {'error': f'invalid think value: {json.dumps(think)} (must be "high", "medium", "low", "max", true, or false)'})
        if think is False and plantilla in ('niveles', 'siempre'):
            # Como el Ollama real con gpt-oss: con think=false responde vacío (sin error)
            time.sleep(RETARDO)
            return self.enviar(200, {'model': modelo, 'message': {'role': 'assistant', 'content': ''},
                                     'done': True, 'done_reason': 'stop', 'prompt_eval_count': 40, 'eval_count': 6})
        opciones = req.get('options') or {}
        num_ctx = opciones.get('num_ctx') or CONTEXTO_POR_DEFECTO_OLLAMA
        mensajes = req.get('messages', [])
        largo = sum(len(m.get('content', '')) for m in mensajes)
        tokens = int(largo / 3.2) + 1
        time.sleep(RETARDO)
        if tokens > num_ctx:
            # Como el Ollama real: se queda con la mitad del contexto y se pierden las instrucciones
            return self.enviar(200, {'model': modelo, 'message': {'role': 'assistant', 'content': 'No entiendo la tarea.'},
                                     'done': True, 'done_reason': 'stop', 'prompt_eval_count': num_ctx // 2 + 3, 'eval_count': 6})
        usuario = [m for m in mensajes if m['role'] == 'user'][0]['content']
        texto = json.dumps(responder_items(usuario), ensure_ascii=False)
        mensaje = {'role': 'assistant', 'content': texto}
        piensa_ahora = piensa and (think is True or think in ('low', 'medium', 'high', 'max') or think is None)
        if piensa_ahora:
            if info.get('texto'):
                mensaje['content'] = '<think>Comparo cada grupo con sus candidatos…</think>\n' + texto   # deepseek-r1
            else:
                mensaje['thinking'] = 'Comparo cada grupo con sus candidatos… ' * (5 if think == 'low' else 40)
        self.enviar(200, {'model': modelo, 'message': mensaje, 'done': True, 'done_reason': 'stop',
                          'prompt_eval_count': tokens, 'eval_count': len(texto) // 3})


if __name__ == '__main__':
    puerto = int(sys.argv[1]) if len(sys.argv) > 1 else 18080
    ThreadingHTTPServer(('127.0.0.1', puerto), H).serve_forever()
