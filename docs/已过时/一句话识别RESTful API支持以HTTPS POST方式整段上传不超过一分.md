一句话识别RESTful API支持以HTTPS POST方式整段上传不超过一分钟的语音文件。识别结果将以JSON格式在请求响应中一次性返回，开发者需要保证在识别结果返回之前连接不中断。

## 功能介绍

请在编码时严格遵循以下要求，否则可能导致识别失败（识别结果为空）。

-   支持的输入格式：单声道（mono）、16 bit采样位数，包括PCM、PCM编码的WAV、OGG封装的OPUS、OGG封装的SPEEX、AMR、MP3、AAC。
    
-   音频采样率：8000 Hz、16000 Hz。
    
-   支持设置返回结果：是否在后处理中添加标点，是否将中文数字转为阿拉伯数字输出。
    
-   支持控制台配置项目热词、定制语言模型。
    
-   支持多种语言识别，语种和方言模型无法在编码时指定，需要在智能语音交互控制台的[全部项目](https://nls-portal.console.aliyun.com/applist)中对相关项目执行**项目功能配置**操作，选择对应的模型。详情请参见[管理项目](https://help.aliyun.com/zh/isi/getting-started/manage-projects#topic-2572199)。
    

**重要**

**不支持纯JavaScript直接调用RESTful接口**：使用纯JavaScript直接访问RESTful接口可能会遇到跨域问题（Cross-Origin Resource Sharing, CORS），并且存在泄露App Key的风险。

## 前提条件

-   已获取项目Appkey，详情请参见[创建项目](https://help.aliyun.com/zh/isi/create-a-project#2572188)。
    
-   已获取Access Token，详情请参见[获取Token概述](https://help.aliyun.com/zh/isi/overview-of-obtaining-an-access-token#587dee8029x7r)。
    

## 交互流程

客户端向服务端发送带有音频数据的HTTPS RESTful POST请求，服务端返回带有识别结果的HTTPS响应。

![交互流程图](https://help-static-aliyun-doc.aliyuncs.com/assets/img/zh-CN/7900770061/p169258.png)

**说明**

服务端的响应除了音频流之外，都会在返回信息的header包含本次识别任务的task\_id参数，是本次请求的唯一标识。

## 服务地址

| 访问类型 | 说明  | URL | Host |
| 外网访问（默认上海地域） | 所有服务器均可使用外网访问URL。 | - 上海：`https://nls-gateway-cn-shanghai.aliyuncs.com/stream/v1/asr` - 北京：`https://nls-gateway-cn-beijing.aliyuncs.com/stream/v1/asr` - 深圳：`https://nls-gateway-cn-shenzhen.aliyuncs.com/stream/v1/asr` | - 上海：`nls-gateway-cn-shanghai.aliyuncs.com` - 北京：`nls-gateway-cn-beijing.aliyuncs.com` - 深圳：`nls-gateway-cn-shenzhen.aliyuncs.com` |
| ECS内网访问 | 使用阿里云上海、北京、深圳ECS（即ECS地域为华东2（上海）、华北2（北京）、华南1（深圳）），可使用内网访问URL。 ECS的经典网络不能访问AnyTunnel，即不能在内网访问语音服务；如果希望使用AnyTunnel，需要创建专有网络在其内部访问。 **说明** - 使用内网访问方式，将不产生ECS实例的公网流量费用。 - 关于ECS的网络类型请参见[网络类型](https://help.aliyun.com/zh/ecs/user-guide/network-types#concept-nfj-dz2-5db)。 | - 上海：`http://nls-gateway-cn-shanghai-internal.aliyuncs.com/stream/v1/asr` - 北京：`http://nls-gateway-cn-beijing-internal.aliyuncs.com/stream/v1/asr` - 深圳：`http://nls-gateway-cn-shenzhen-internal.aliyuncs.com/stream/v1/asr` | - 上海：`nls-gateway-cn-shanghai-internal.aliyuncs.com` - 北京：`nls-gateway-cn-beijing-internal.aliyuncs.com` - 深圳：`nls-gateway-cn-shenzhen-internal.aliyuncs.com` |

**重要**

-   支持的协议类型：
    
    -   外网访问：支持HTTP和HTTPS协议。
        
    -   ECS内网访问：只支持HTTP协议。
        
-   本文的示例使用的是外网访问的方式。在实际业务场景中，请根据实际情况适配URL。
    

## **输入音频**

### **上传二进制音频流**

一句话识别请求HTTPS报文实例。

```
POST /stream/v1/asr?appkey=23f5****&format=pcm&sample_rate=16000&enable_punctuation_prediction=true&enable_inverse_text_normalization=true HTTP/1.1
X-NLS-Token: 450372e4279bcc2b3c793****
Content-type: application/octet-stream
Content-Length: 94616
Host: nls-gateway-cn-shanghai.aliyuncs.com

[audio data]
```

一个完整的一句话识别RESTful API请求需包含以下要素：HTTPS请求行、HTTPS请求头部和HTTPS请求体。

-   HTTPS请求行
    
    HTTPS请求行指定了**URL**和**请求参数**，由URL和请求参数组成的完整请求链接如下：
    
    ```
    https://nls-gateway-cn-shanghai.aliyuncs.com/stream/v1/asr?appkey=Yu1******uncS&format=pcm&sample_rate=16000&vocabulary_id=a17******d6b&customization_id=abd******ed8&enable_punctuation_prediction=true&enable_inverse_text_normalization=true&enable_voice_detection=true
    ```
    
    -   URL
        
        | 协议  | URL | 方法  |
        | --- | --- | --- |
        | HTTP/1.1 | nls-gateway-cn-shanghai.aliyuncs.com/stream/v1/asr | POST |
        
    -   请求参数
        
        | 参数  | 类型  | 是否必选 | 描述  |
        | --- | --- | --- | --- |
        | appkey | String | 是   | 应用Appkey。 获取Appkey请前往[控制台](https://nls-portal.console.aliyun.com/applist)。 |
        | format | String | 否   | 音频格式，包括PCM、WAV、OPUS、SPEEX、AMR、MP3、AAC。 |
        | sample\\_rate | Integer | 否   | 音频采样率。取值：16000 Hz、8000 Hz。默认值：16000 Hz。 |
        | vocabulary\\_id | String | 否   | 添加热词表ID。默认：不添加。 |
        | customization\\_id | String | 否   | 添加自学习模型ID。默认：不添加。 |
        | enable\\_punctuation\\_prediction | Boolean | 否   | 是否在后处理中添加标点，默认值：False（关闭）。 |
        | enable\\_inverse\\_text\\_normalization | Boolean | 否   | ITN（逆文本inverse text normalization）中文数字转换阿拉伯数字。设置为True时，中文数字将转为阿拉伯数字输出，默认值：False。 |
        | enable\\_voice\\_detection | Boolean | 否   | 是否启动语音检测。开启后能够识别出一段音频中有效语音的开始和结束，剔除噪音数据。默认值：False（不开启）。 |
        | disfluency | Boolean | 否   | 过滤语气词，即声音顺滑，默认值：False（关闭）。 |
        
-   HTTPS请求头部
    
    HTTPS请求头部由“关键字-值”对组成，每行一对，关键字和值用英文冒号“:”分隔，设置内容如下：
    
    | 名称  | 类型  | 是否必选 | 描述  |
    | --- | --- | --- | --- |
    | X-NLS-Token | String | 是   | 服务鉴权Token。 |
    | Content-type | String | 是   | 取值“application/octet-stream”，表明HTTPS请求体的数据为二进制流。 |
    | Content-Length | long | 是   | HTTPS请求体中请求数据的长度，即音频文件的长度。 |
    | Host | String | 是   | 取值“nls-gateway-cn-shanghai.aliyuncs.com”，为HTTPS请求的服务器域名。 |
    
-   HTTPS请求体
    
    HTTPS请求体传入的是二进制音频数据，因此在HTTPS请求头部中的`Content-Type`必须设置为`application/octet-stream`。
    

### **使用音频文件链接**

一句话识别请求HTTPS报文实例。

```
POST /stream/v1/asr?appkey=23f5****&format=pcm&sample_rate=16000&enable_punctuation_prediction=true&enable_inverse_text_normalization=true&audio_address=your-audio-url HTTP/1.1
X-NLS-Token: 450372e4279bcc2b3c793****
Host: nls-gateway-cn-shanghai.aliyuncs.com
```

一个完整的一句话识别RESTful API请求需包含以下要素：HTTPS请求行、HTTPS请求头部和HTTPS请求体。

-   HTTPS请求行
    
    HTTPS请求行指定了**URL**和**请求参数**，由URL和请求参数组成的完整请求链接如下：
    
    ```
    https://nls-gateway-cn-shanghai.aliyuncs.com/stream/v1/asr?appkey=Yu1******uncS&format=pcm&sample_rate=16000&vocabulary_id=a17******d6b&customization_id=abd******ed8&enable_punctuation_prediction=true&enable_inverse_text_normalization=true&enable_voice_detection=true&audio_address=your-audio-url
    ```
    
    -   URL
        
        | 协议  | URL | 方法  |
        | --- | --- | --- |
        | HTTP/1.1 | nls-gateway-cn-shanghai.aliyuncs.com/stream/v1/asr | POST |
        
    -   请求参数
        
        | 参数  | 类型  | 是否必选 | 描述  |
        | --- | --- | --- | --- |
        | appkey | String | 是   | 应用Appkey。 获取Appkey请前往[控制台](https://nls-portal.console.aliyun.com/applist)。 |
        | format | String | 否   | 音频格式，包括PCM、WAV、OPUS、SPEEX、AMR、MP3、AAC。 |
        | sample\\_rate | Integer | 否   | 音频采样率。取值：16000 Hz、8000 Hz。默认值：16000 Hz。 |
        | vocabulary\\_id | String | 否   | 添加热词表ID。默认：不添加。 |
        | customization\\_id | String | 否   | 添加自学习模型ID。默认：不添加。 |
        | enable\\_punctuation\\_prediction | Boolean | 否   | 是否在后处理中添加标点，默认值：False（关闭）。 |
        | enable\\_inverse\\_text\\_normalization | Boolean | 否   | ITN（逆文本inverse text normalization）中文数字转换阿拉伯数字。设置为True时，中文数字将转为阿拉伯数字输出，默认值：False。 |
        | enable\\_voice\\_detection | Boolean | 否   | 是否启动语音检测。开启后能够识别出一段音频中有效语音的开始和结束，剔除噪音数据。默认值：False（不开启）。 |
        | disfluency | Boolean | 否   | 过滤语气词，即声音顺滑，默认值：False（关闭）。 |
        | audio\\_address | String | 否   | 可通过公网访问的音频文件下载链接。推荐使用阿里云OSS，具体请参见[通过OSS如何获取访问URL](https://help.aliyun.com/zh/oss/user-guide/how-to-obtain-the-url-of-a-single-object-or-the-urls-of-multiple-objects)。 |
        
-   HTTPS请求头部
    
    HTTPS请求头部由“关键字-值”对组成，每行一对，关键字和值用英文冒号“:”分隔，设置内容如下：
    
    | 名称  | 类型  | 是否必选 | 描述  |
    | --- | --- | --- | --- |
    | X-NLS-Token | String | 是   | 服务鉴权Token。 |
    | Host | String | 是   | 取值“nls-gateway-cn-shanghai.aliyuncs.com”，为HTTPS请求的服务器域名。 |
    

## 响应结果

发送上传音频的HTTPS请求后，会收到服务端的响应，识别结果以JSON字符串的形式保存在响应结果中。

-   成功响应
    
    ```
    {
        "task_id": "cf7b0c5339244ee29cd4e43fb97f****",
        "result": "北京的天气。",
        "status":20000000,
        "message":"SUCCESS"
    }
    ```
    
-   失败响应
    
    以鉴权token无效为例：
    
    ```
    {
        "task_id": "8bae3613dfc54ebfa811a17d8a7a****",
        "result": "",
        "status": 40000001,
        "message": "Gateway:ACCESS_DENIED:The token 'c0c1e860f3*******de8091c68a' is invalid!"
    }
    ```
    
    | 参数  | 类型  | 描述  |
    | --- | --- | --- |
    | task\\_id | String | 32位任务ID。请您记录该值，以便排查错误。 |
    | result | String | 语音识别结果。 |
    | status | Integer | 服务状态码。 |
    | message | String | 服务状态描述。 |
    

## 服务状态码

| 服务状态码 | 服务状态描述 | 解决方案 |
| --- | --- | --- |
| 20000000 | 请求成功 | 无。  |
| 40000000 | 默认的客户端错误码 | 检查对应的错误消息。 |
| 40000001 | 身份认证失败 | 检查使用的令牌是否正确、是否过期。 |
| 40000002 | 无效的消息 | 检查发送的消息是否符合要求。 |
| 40000003 | 无效的参数 | 检查参数值设置是否合理。 |
| 40000004 | 空闲超时 | 确认是否长时间没有发送数据到服务端。 |
| 40000005 | 请求数量过多 | 检查是否超过了并发连接数或者每秒钟请求数。 |
| 40000010 | 新用户免费试用3个月已到期 | 继续使用需要付费商用，请前往[控制台](https://nls-portal.console.aliyun.com/)，在**服务管理与开通**页面，单击目标服务右侧的**升级为商用版**，进行付费使用。 |
| 41010101 | 不支持的采样率 | 检查代码中设置的采样率参数（8000/16000）与管控台上Appkey对应的模型（8k/16k）是否匹配。 |
| 50000000 | 默认的服务端错误 | 内部服务错误，需要客户端进行重试。 |
| 50000001 | 内部GRPC调用错误 | 内部服务错误，需要客户端进行重试。 |

## 快速测试

**说明**

音频示例WAV文件，使用通用模型。若使用其他音频文件，请填入相应的编码格式和采样率，并在管控台设置对应的模型。

1.  [下载音频文件nls-sample-16k.wav](https://gw.alipayobjects.com/os/bmw-prod/0574ee2e-f494-45a5-820f-63aee583045a.wav)。
    
2.  使用如下cURL命令行进行一句话识别的RESTful API测试。
    
    ```
    curl -X POST -H "X-NLS-Token: ${token}" https://nls-gateway-cn-shanghai.aliyuncs.com/stream/v1/asr?appkey=${appkey} --data-binary @${audio_file}
    ```
    
    举例如下：
    
    ```
    curl -X POST -H "X-NLS-Token: 4a036531cfdd****" https://nls-gateway-cn-shanghai.aliyuncs.com/stream/v1/asr?appkey=tt43P2u**** --data-binary @./nls-sample-16k.wav
    ```
    

## Java示例

依赖文件如下：

```
<dependency>
    <groupId>com.squareup.okhttp3</groupId>
    <artifactId>okhttp</artifactId>
    <version>3.9.1</version>
</dependency>

<!-- http://mvnrepository.com/artifact/com.alibaba/fastjson -->
<dependency>
    <groupId>com.alibaba</groupId>
    <artifactId>fastjson</artifactId>
    <version>1.2.83</version>
</dependency>
```

发送请求与响应：

```
import com.alibaba.fastjson.JSONPath;
import com.alibaba.nls.client.example.utils.HttpUtil;
import java.util.HashMap;
public class SpeechRecognizerRESTfulDemo {
    private String accessToken;
    private String appkey;

    public SpeechRecognizerRESTfulDemo(String appkey, String token) {
        this.appkey = appkey;
        this.accessToken = token;
    }

    public void process(String fileName, String format, int sampleRate,
                        boolean enablePunctuationPrediction,
                        boolean enableInverseTextNormalization,
                        boolean enableVoiceDetection) {

        /**
         * 设置HTTPS RESTful POST请求：
         * 1.使用HTTPS协议。
         * 2.语音识别服务域名：nls-gateway-cn-shanghai.aliyuncs.com。
         * 3.语音识别接口请求路径：/stream/v1/asr。
         * 4.设置必选请求参数：appkey、format、sample_rate。
         * 5.设置可选请求参数：enable_punctuation_prediction、enable_inverse_text_normalization、enable_voice_detection。
         */
        String url = "https://nls-gateway-cn-shanghai.aliyuncs.com/stream/v1/asr";
        String request = url;
        request = request + "?appkey=" + appkey;
        request = request + "&format=" + format;
        request = request + "&sample_rate=" + sampleRate;
        if (enablePunctuationPrediction) {
            request = request + "&enable_punctuation_prediction=" + true;
        }
        if (enableInverseTextNormalization) {
            request = request + "&enable_inverse_text_normalization=" + true;
        }
        if (enableVoiceDetection) {
            request = request + "&enable_voice_detection=" + true;
        }

        System.out.println("Request: " + request);

        /**
         * 设置HTTPS头部字段：
         * 1.鉴权参数。
         * 2.Content-Type：application/octet-stream。
         */
        HashMap<String, String> headers = new HashMap<String, String>();
        headers.put("X-NLS-Token", this.accessToken);
        headers.put("Content-Type", "application/octet-stream");

        /**
         * 发送HTTPS POST请求，返回服务端的响应。
         */
        String response = HttpUtil.sendPostFile(request, headers, fileName);

        if (response != null) {
            System.out.println("Response: " + response);
            String result = JSONPath.read(response, "result").toString();
            System.out.println("识别结果：" + result);
        }
        else {
            System.err.println("识别失败!");
        }

    }

    public static void main(String[] args) {
        if (args.length < 2) {
            System.err.println("SpeechRecognizerRESTfulDemo need params: <token> <app-key>");
            System.exit(-1);
        }

        String token = args[0];
        String appkey = args[1];

        SpeechRecognizerRESTfulDemo demo = new SpeechRecognizerRESTfulDemo(appkey, token);

        String fileName = SpeechRecognizerRESTfulDemo.class.getClassLoader().getResource("./nls-sample-16k.wav").getPath();
        String format = "pcm";
        int sampleRate = 16000;
        boolean enablePunctuationPrediction = true;
        boolean enableInverseTextNormalization = true;
        boolean enableVoiceDetection = false;

        demo.process(fileName, format, sampleRate, enablePunctuationPrediction, enableInverseTextNormalization, enableVoiceDetection);
    }
}
```

其中，HttpUtils类如下：

```
import okhttp3.*;
import java.io.File;
import java.io.IOException;
import java.net.SocketTimeoutException;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;

public class HttpUtil {

    private static String getResponseWithTimeout(Request q) {
        String ret = null;

        OkHttpClient.Builder httpBuilder = new OkHttpClient.Builder();
        OkHttpClient client = httpBuilder.connectTimeout(10, TimeUnit.SECONDS)
                .readTimeout(60, TimeUnit.SECONDS)
                .writeTimeout(60, TimeUnit.SECONDS)
                .build();

        try {
            Response s = client.newCall(q).execute();
            ret = s.body().string();
            s.close();
        } catch (SocketTimeoutException e) {
            ret = null;
            System.err.println("get result timeout");
        } catch (IOException e) {
            System.err.println("get result error " + e.getMessage());
        }

        return ret;
    }

    public static String sendPostFile(String url, HashMap<String, String> headers, String fileName) {
        RequestBody body;

        File file = new File(fileName);
        if (!file.isFile()) {
            System.err.println("The filePath is not a file: " + fileName);
            return null;
        } else {
            body = RequestBody.create(MediaType.parse("application/octet-stream"), file);
        }

        Headers.Builder hb = new Headers.Builder();
        if (headers != null && !headers.isEmpty()) {
            for (Map.Entry<String, String> entry : headers.entrySet()) {
                hb.add(entry.getKey(), entry.getValue());
            }
        }

        Request request = new Request.Builder()
                .url(url)
                .headers(hb.build())
                .post(body)
                .build();

        return getResponseWithTimeout(request);
    }

    public static String sendPostData(String url, HashMap<String, String> headers, byte[] data) {
        RequestBody body;

        if (data.length == 0) {
            System.err.println("The send data is empty.");
            return null;
        } else {
            body = RequestBody.create(MediaType.parse("application/octet-stream"), data);
        }

        Headers.Builder hb = new Headers.Builder();
        if (headers != null && !headers.isEmpty()) {
            for (Map.Entry<String, String> entry : headers.entrySet()) {
                hb.add(entry.getKey(), entry.getValue());
            }
        }

        Request request = new Request.Builder()
                .url(url)
                .headers(hb.build())
                .post(body)
                .build();

        return getResponseWithTimeout(request);
    }
}
```

## C++示例

C++示例使用第三方函数库curl处理HTTPS请求和响应。[下载curl库和Demo文件](https://help-static-aliyun-doc.aliyuncs.com/file-manage-files/zh-CN/20230223/vtkm/NlsCommonSdk.zip)。

目录说明如下所示。

| 文件/文件夹 | 说明  |
| --- | --- |
| CMakeLists.txt | 示例工程的CMakeList文件。 |
| demo | 其中的restfulAsrDemo.cpp是一句话识别RESTful API Demo。 |
| include | 其中curl文件夹是curl库头文件目录。 |
| lib | 包含curl动态库，版本为curl-7.60。根据平台不同选如下版本： - Linux：Glibc 2.5、Gcc 4/Gcc 5。 - Windows：VS2013、VS2015。 |
| readme.txt | 说明。 |
| release.log | 更新记录。 |
| version | 版本号。 |
| build.sh | 示例编译脚本。 |

编译运行操作步骤：

假设示例文件已解压至`path/to`路径下，在Linux终端依次执行如下命令编译运行程序。

-   支持Cmake：
    
    1.  确认本地系统已安装Cmake 2.4及以上版本。
        
    2.  `cd path/to/sdk/lib`。
        
    3.  `tar -zxvpf linux.tar.gz`。
        
    4.  `cd path/to/sdk`。
        
    5.  `./build.sh`。
        
    6.  `cd path/to/sdk/demo`。
        
    7.  `./restfulAsrDemo <your-token> <your-appkey>`。
        
-   不支持Cmake：
    
    1.  `cd path/to/sdk/lib`。
        
    2.  `tar -zxvpf linux.tar.gz`。
        
    3.  `cd path/to/sdk/demo`。
        
    4.  `g++ -o restfulAsrDemo restfulAsrDemo.cpp -I path/to/sdk/include -L path/to/sdk/lib/linux -lssl -lcrypto -lcurl -D_GLIBCXX_USE_CXX11_ABI=0`。
        
    5.  `export LD_LIBRARY_PATH=path/to/sdk/lib/linux/`。
        
    6.  `./restfulAsrDemo <your-token> <your-appkey>`。
        

```
#include <iostream>
#include <string>
#include <fstream>
#include <sstream>
#include "curl/curl.h"

using namespace std;

#ifdef _WIN32
string UTF8ToGBK(const string& strUTF8) {
    int len = MultiByteToWideChar(CP_UTF8, 0, strUTF8.c_str(), -1, NULL, 0);
    unsigned short * wszGBK = new unsigned short[len + 1];
    memset(wszGBK, 0, len * 2 + 2);

    MultiByteToWideChar(CP_UTF8, 0, (char*)strUTF8.c_str(), -1, (wchar_t*)wszGBK, len);

    len = WideCharToMultiByte(CP_ACP, 0, (wchar_t*)wszGBK, -1, NULL, 0, NULL, NULL);

    char *szGBK = new char[len + 1];
    memset(szGBK, 0, len + 1);
    WideCharToMultiByte(CP_ACP, 0, (wchar_t*)wszGBK, -1, szGBK, len, NULL, NULL);

    string strTemp(szGBK);
    delete[] szGBK;
    delete[] wszGBK;

    return strTemp;
}

#endif

/**
 * 一句话识别RESTful API HTTPS请求的响应回调函数
 * 识别结果为JSON格式的字符串
 */
size_t responseCallback(void* ptr, size_t size, size_t nmemb, void* userData) {
    string* srResult = (string*)userData;

    size_t len = size * nmemb;
    char *pBuf = (char*)ptr;
    string response = string(pBuf, pBuf + len);
#ifdef _WIN32
    response = UTF8ToGBK(response);
#endif
    cout << "current result: " << response << endl;

    *srResult += response;
    cout << "total result: " << *srResult << endl;

    return len;
}

int sendAsrRequest(const char* request, const char* token, const char* fileName, string* srResult) {
    CURL* curl = NULL;
    CURLcode res;

    /**
    * 读取音频文件
    */
    ifstream fs;
    fs.open(fileName, ios::out | ios::binary);
    if (!fs.is_open()) {
        cerr << "The audio file is not exist!" << endl;
        return -1;
    }
    stringstream buffer;
    buffer << fs.rdbuf();
    string audioData(buffer.str());

    curl = curl_easy_init();
    if (curl == NULL) {
        return -1;
    }

    /**
    * 设置HTTPS请求行
    */
    curl_easy_setopt(curl, CURLOPT_CUSTOMREQUEST, "POST");
    curl_easy_setopt(curl, CURLOPT_URL, request);

    /**
    * 设置HTTPS请求头部
    */
    struct curl_slist* headers = NULL;
    // token
    string X_NLS_Token = "X-NLS-Token:";
    X_NLS_Token += token;
    headers = curl_slist_append(headers, X_NLS_Token.c_str());
    // Content-Type
    headers = curl_slist_append(headers, "Content-Type:application/octet-stream");
    // Content-Length
    string content_Length = "Content-Length:";
    ostringstream oss;
    oss << content_Length << audioData.length();
    content_Length = oss.str();
    headers = curl_slist_append(headers, content_Length.c_str());

    curl_easy_setopt(curl, CURLOPT_HTTPHEADER, headers);

    /**
    * 设置HTTPS请求数据
    */
    curl_easy_setopt(curl, CURLOPT_POSTFIELDS, audioData.c_str());
    curl_easy_setopt(curl, CURLOPT_POSTFIELDSIZE, audioData.length());

    /**
    * 设置HTTPS请求的响应回调函数
    */
    curl_easy_setopt(curl, CURLOPT_WRITEFUNCTION, responseCallback);
    curl_easy_setopt(curl, CURLOPT_WRITEDATA, srResult);

    /**
    * 发送HTTPS请求
    */
    res = curl_easy_perform(curl);

    // 释放资源
    curl_slist_free_all(headers);
    curl_easy_cleanup(curl);

    if (res != CURLE_OK) {
        cerr << "curl_easy_perform failed: " << curl_easy_strerror(res) << endl;
        return -1;
    }

    return 0;
}

int process(const char* request, const char* token, const char* fileName) {
    // 全局只初始化一次
    curl_global_init(CURL_GLOBAL_ALL);

    string srResult = "";
    int ret = sendAsrRequest(request, token, fileName, &srResult);

    curl_global_cleanup();

    return ret;
}

int main(int argc, char* argv[]) {
    if (argc < 3) {
        cerr << "params is not valid. Usage: ./demo your_token your_appkey" << endl;
        return -1;
    }

    string token = argv[1];
    string appKey = argv[2];

    string url = "https://nls-gateway-cn-shanghai.aliyuncs.com/stream/v1/asr";
    string format = "pcm";
    int sampleRate = 16000;
    bool enablePunctuationPrediction = true;
    bool enableInverseTextNormalization = true;
    bool enableVoiceDetection = false;
    string fileName = "sample.pcm";

    /**
    * 设置RESTful请求参数
    */
    ostringstream oss;
    oss << url;
    oss << "?appkey=" << appKey;
    oss << "&format=" << format;
    oss << "&sample_rate=" << sampleRate;
    if (enablePunctuationPrediction) {
        oss << "&enable_punctuation_prediction=" << "true";
    }
    if (enableInverseTextNormalization) {
        oss << "&enable_inverse_text_normalization=" << "true";
    }
    if (enableVoiceDetection) {
        oss << "&enable_voice_detection=" << "true";
    }

    string request = oss.str();
    cout << "request: " << request << endl;

    process(request.c_str(), token.c_str(), fileName.c_str());

    return 0;
}
```

## Python示例

**说明**

-   Python 2.x请使用httplib模块；Python 3.x请使用http.client模块。
    
-   创建HTTP/HTTPS连接时，Python 2.x使用httplib模块，Python 3.x使用http.client模块：
    
    ## Python 2.x
    
    ## 创建HTTP连接
    
    ```
    import httplib
    
    host = 'nls-gateway-cn-shanghai.aliyuncs.com'
    conn = httplib.HTTPConnection(host)
    ```
    
    ## 创建HTTPS连接
    
    ```
    import httplib
    
    host = 'nls-gateway-cn-shanghai.aliyuncs.com'
    conn = httplib.HTTPSConnection(host)
    ```
    
    ## Python 3.x
    
    ## 创建HTTP连接
    
    ```
    import http.client
    
    host = 'nls-gateway-cn-shanghai.aliyuncs.com'
    conn = http.client.HTTPConnection(host)
    ```
    
    ## 创建HTTPS连接
    
    ```
    import http.client
    
    host = 'nls-gateway-cn-shanghai.aliyuncs.com'
    conn = http.client.HTTPSConnection(host)
    ```
    
-   采用RFC 3986规范进行urlencode编码，Python 2.x请使用urllib模块的urllib.quote，Python 3.x请使用urllib.parse模块的urllib.parse.quote\_plus。
    

```
# -*- coding: UTF-8 -*-
# Python 2.x引入httplib模块
# import httplib
# Python 3.x引入http.client模块
import http.client

import json

def process(request, token, audioFile) :

    # 读取音频文件
    with open(audioFile, mode = 'rb') as f:
        audioContent = f.read()

    host = 'nls-gateway-cn-shanghai.aliyuncs.com'

    # 设置HTTPS请求头部
    httpHeaders = {
        'X-NLS-Token': token,
        'Content-type': 'application/octet-stream',
        'Content-Length': len(audioContent)
        }

    # Python 2.x使用httplib
    # conn = httplib.HTTPSConnection(host)
    
    # Python 3.x使用http.client
    conn = http.client.HTTPSConnection(host)

    conn.request(method='POST', url=request, body=audioContent, headers=httpHeaders)

    response = conn.getresponse()
    print('Response status and response reason:')
    print(response.status ,response.reason)

    body = response.read()
    try:
        print('Recognize response is:')
        body = json.loads(body)
        print(body)

        status = body['status']
        if status == 20000000 :
            result = body['result']
            print('Recognize result: ' + result)
        else :
            print('Recognizer failed!')

    except ValueError:
        print('The response is not json format string')

    conn.close()



appKey = '填入appkey'
token = '填入服务鉴权Token'

# 服务请求地址
url = 'https://nls-gateway-cn-shanghai.aliyuncs.com/stream/v1/asr'

# 音频文件
audioFile = '/path/to/nls-sample-16k.wav'
format = 'pcm'
sampleRate = 16000
enablePunctuationPrediction  = True
enableInverseTextNormalization = True
enableVoiceDetection  = False

# 设置RESTful请求参数
request = url + '?appkey=' + appKey
request = request + '&format=' + format
request = request + '&sample_rate=' + str(sampleRate)

if enablePunctuationPrediction :
    request = request + '&enable_punctuation_prediction=' + 'true'

if enableInverseTextNormalization :
    request = request + '&enable_inverse_text_normalization=' + 'true'

if enableVoiceDetection :
    request = request + '&enable_voice_detection=' + 'true'

print('Request: ' + request)


process(request, token, audioFile)

```

## PHP示例

**说明**

PHP示例中使用了cURL函数，要求PHP版本4.0.2以上，并且已安装cURL扩展。

```
<?php

function process($token, $request, $audioFile) {
    /**
     * 读取音频文件
     */
    $audioContent = file_get_contents($audioFile);
    if ($audioContent == FALSE) {
        print "The audio file is not exist!\n";
        return;
    }

    $curl = curl_init();
    curl_setopt($curl, CURLOPT_RETURNTRANSFER, 1);
    curl_setopt($curl, CURLOPT_TIMEOUT, 120);

    /**
     * 设置HTTPS请求行
     */
    curl_setopt($curl, CURLOPT_URL, $request);
    curl_setopt($curl, CURLOPT_POST,TRUE);

    /**
     * 设置HTTPS请求头部
     */
    $contentType = "application/octet-stream";
    $contentLength = strlen($audioContent);
    $headers = array(
        "X-NLS-Token:" . $token,
        "Content-type:" . $contentType,
        "Content-Length:" . strval($contentLength)
    );
    curl_setopt($curl, CURLOPT_HTTPHEADER, $headers);

    /**
     * 设置HTTPS请求数据
     */
    curl_setopt($curl, CURLOPT_POSTFIELDS, $audioContent);
    curl_setopt($curl, CURLOPT_NOBODY, FALSE);

    /**
     * 发送HTTPS请求
     */
    $returnData = curl_exec($curl);

    curl_close($curl);

    if ($returnData == FALSE) {
        print "curl_exec failed!\n";
        return;
    }

    print $returnData . "\n";

    $resultArr = json_decode($returnData, true);

    $status = $resultArr["status"];
    if ($status == 20000000) {
        $result = $resultArr["result"];
        print "The audio file recognized result: " . $result . "\n";
    }
    else {
        print "The audio file recognized failed.\n";
    }

}


$appkey = "填入appkey";
$token = "填入服务鉴权Token";

$url = "https://nls-gateway-cn-shanghai.aliyuncs.com/stream/v1/asr";
$audioFile = "/path/to/nls-sample-16k.wav";
$format = "pcm";
$sampleRate = 16000;
$enablePunctuationPrediction = TRUE;
$enableInverseTextNormalization = TRUE;
$enableVoiceDetection = FALSE;

/**
 * 设置RESTful请求参数
 */
$request = $url;
$request = $request . "?appkey=" . $appkey;
$request = $request . "&format=" . $format;
$request = $request . "&sample_rate=" . strval($sampleRate);
if ($enablePunctuationPrediction) {
    $request = $request . "&enable_punctuation_prediction=" . "true";
}
if ($enableInverseTextNormalization) {
    $request = $request . "&enable_inverse_text_normalization=" . "true";
}
if ($enableVoiceDetection) {
    $request = $request . "&enable_voice_detection=" . "true";
}

print "Request: " . $request . "\n";

process($token, $request, $audioFile);

?>

```

## Node.js示例

**说明**

在示例文件所在目录执行如下命令安装request依赖：`npm install request --save`

```
const request = require('request');
const fs = require('fs');


function callback(error, response, body) {
        if (error != null) {
                console.log(error);
        }
        else {
                console.log('The audio file recognized result:');
                console.log(body);
                if (response.statusCode == 200) {
                        body = JSON.parse(body);
                        if (body.status == 20000000) {
                                console.log('result: ' + body.result);
                                console.log('The audio file recognized succeed!');
                        } else {
                                console.log('The audio file recognized failed!');
                        }
                } else {
                        console.log('The audio file recognized failed, http code: ' + response.statusCode);
                }
        }
}

function process(requestUrl, token, audioFile) {
        /**
         * 读取音频文件
        */
        var audioContent = null;
        try {
                audioContent = fs.readFileSync(audioFile);
        } catch(error) {
                if (error.code == 'ENOENT') {
                        console.log('The audio file is not exist!');
                }
                return;
        }

        /**
         * 设置HTTPS请求头部
        */
        var httpHeaders = {
                'X-NLS-Token': token,
                'Content-type': 'application/octet-stream',
                'Content-Length': audioContent.length
        };

        var options = {
                url: requestUrl,
                method: 'POST',
                headers: httpHeaders,
                body: audioContent
        };

        request(options, callback);
}


var appkey = '填入appkey';
var token = '填入服务鉴权Token';

var url = 'https://nls-gateway-cn-shanghai.aliyuncs.com/stream/v1/asr';
var audioFile = '/path/to/nls-sample-16k.wav';
var format = 'pcm';
var sampleRate = '16000';
var enablePunctuationPrediction = true;
var enableInverseTextNormalization = true;
var enableVoiceDetection  = false;

/**
 * 设置RESTful请求参数
 */
var requestUrl = url;
requestUrl = requestUrl + '?appkey=' + appkey;
requestUrl = requestUrl + '&format=' + format;
requestUrl = requestUrl + '&sample_rate=' + sampleRate;
if (enablePunctuationPrediction) {
        requestUrl = requestUrl + '&enable_punctuation_prediction=' + 'true';
}
if (enableInverseTextNormalization) {
        requestUrl = requestUrl + '&enable_inverse_text_normalization=' + 'true';
}
if (enableVoiceDetection) {
        requestUrl = requestUrl + '&enable_voice_detection=' + 'true';
}

process(requestUrl, token, audioFile);

```

## .Net示例

**说明**

示例依赖System.Net.Http和Newtonsoft.Json.Linq。

```
using System;
using System.Net.Http;
using System.IO;
using Newtonsoft.Json.Linq;

namespace RESTfulAPI
{
    class SpeechRecognizerRESTfulDemo
    {
        private string token;
        private string appkey;

        public SpeechRecognizerRESTfulDemo(string appkey, string token)
        {
            this.appkey = appkey;
            this.token = token;
        }

        public void process(string fileName, string format, int sampleRate,
            bool enablePunctuationPrediction,
            bool enableInverseTextNormalization,
            bool enableVoiceDetection)
        {
            /**
             * 设置HTTPS REST POST请求
             * 1.使用HTTPS协议
             * 2.语音识别服务域名：nls-gateway-cn-shanghai.aliyuncs.com
             * 3.语音识别接口请求路径：/stream/v1/asr
             * 4.设置必选请求参数：appkey、format、sample_rate，
             * 5.设置可选请求参数：enable_punctuation_prediction、enable_inverse_text_normalization、enable_voice_detection
             */
            string url = "https://nls-gateway-cn-shanghai.aliyuncs.com/stream/v1/asr";
            url = url + "?appkey=" + appkey;
            url = url + "&format=" + format;
            url = url + "&sample_rate=" + sampleRate;
            if (enablePunctuationPrediction)
            {
                url = url + "&enable_punctuation_prediction=" + true;
            }
            if (enableInverseTextNormalization)
            {
                url = url + "&enable_inverse_text_normalization=" + true;
            }
            if (enableVoiceDetection)
            {
                url = url + "&enable_voice_detection=" + true;
            }

            System.Console.WriteLine("URL: " + url);


            HttpClient client = new HttpClient();
            /**
             * 设置HTTPS头部字段
             * 鉴权参数
             */
            client.DefaultRequestHeaders.Add("X-NLS-Token", token);
            if (!File.Exists(fileName))
            {
                System.Console.WriteLine("The audio file dose not exist");
                return;
            }
            byte[] audioData = File.ReadAllBytes(fileName);

            /**
             * 设置HTTPS Body
             * 音频二进制数据
             * Content-Type：application/octet-stream
             */
            ByteArrayContent content = new ByteArrayContent(audioData);
            content.Headers.Add("Content-Type", "application/octet-stream");

            /**
             * 发送HTTPS POST请求，处理服务端的响应。
             */
            HttpResponseMessage response = client.PostAsync(url, content).Result;

            string responseBodyAsText = response.Content.ReadAsStringAsync().Result;
            System.Console.WriteLine("Response: " + responseBodyAsText);

            if (response.IsSuccessStatusCode)
            {
                JObject obj = JObject.Parse(responseBodyAsText);
                string result = obj["result"].ToString();
                System.Console.WriteLine("识别结果： " + result);
            }
            else
            {
                System.Console.WriteLine("Response status code and reason phrase: " +
                    response.StatusCode + " " + response.ReasonPhrase);
                System.Console.WriteLine("识别失败！");
            }

        }

        static void Main(string[] args)
        {
            if (args.Length < 2)
            {
                System.Console.WriteLine("SpeechRecognizerRESTfulDemo need params: <token> <app-key>");
                return;
            }

            string token = args[0];
            string appkey = args[1];

            SpeechRecognizerRESTfulDemo demo = new SpeechRecognizerRESTfulDemo(appkey, token);

            string fileName = "nls-sample-16k.wav";
            string format = "pcm";
            int sampleRate = 16000;
            bool enablePunctuationPrediction = true;
            bool enableInverseTextNormalization = true;
            bool enableVoiceDetection = false;

            demo.process(fileName, format, sampleRate,
                enablePunctuationPrediction, enableInverseTextNormalization, enableVoiceDetection);

        }
    }
}

```

## GO示例

```
package main

import (
    "fmt"
        "encoding/json"
        "net/http"
        "io/ioutil"
        "strconv"
        "bytes"
)

func process(appkey string, token string, fileName string, format string, sampleRate int,
             enablePunctuationPrediction bool, enableInverseTextNormalization bool, enableVoiceDetection bool) {
                         
    /**
     * 设置HTTPS REST POST请求：
     * 1.使用HTTPS协议
     * 2.语音识别服务域名：nls-gateway-cn-shanghai.aliyuncs.com
     * 3.语音识别接口请求路径：/stream/v1/asr
     * 4.设置必选请求参数：appkey、format、sample_rate，
     * 5.设置可选请求参数：enable_punctuation_prediction、enable_inverse_text_normalization、enable_voice_detection
     */                 
        var url string = "https://nls-gateway-cn-shanghai.aliyuncs.com/stream/v1/asr"
        url = url + "?appkey=" + appkey
        url = url + "&format=" + format
        url = url + "&sample_rate=" + strconv.Itoa(sampleRate)
        if (enablePunctuationPrediction) {
            url = url + "&enable_punctuation_prediction=" + "true"
        }
        if (enableInverseTextNormalization) {
            url = url + "&enable_inverse_text_normalization=" + "true"
        }
        if (enableVoiceDetection) {
            url = url + "&enable_voice_detection=" + "false"
        }

    fmt.Println(url)
        
        /**
         * 读取音频数据，作为HTTPS请求体。
         。*/
        audioData, err := ioutil.ReadFile(fileName)
        if err != nil {
            panic(err)
        }
        
        request, err := http.NewRequest("POST", url, bytes.NewBuffer(audioData))
        if err != nil {
            panic(err)
        }
        
        /**
     * 设置HTTPS头部字段：
     * 1.鉴权参数。
     * 2.Content-Type：application/octet-stream。
     */
        request.Header.Add("X-NLS-Token", token)
        request.Header.Add("Content-Type", "application/octet-stream")
        
        /**
     * 发送HTTPS POST请求，处理服务端的响应。
     */
        client := &http.Client{}
        response, err := client.Do(request)
        if err != nil {
            panic(err)
        }
        
        defer response.Body.Close()
        
        body, _ := ioutil.ReadAll(response.Body)
        fmt.Println(string(body))
        
        statusCode := response.StatusCode
        if (statusCode == 200) {
            var resultMap map[string]interface{}
            err = json.Unmarshal([]byte(body), &resultMap)
            if err != nil {
                panic(err)
            }
        
            var result string = resultMap["result"].(string)
            fmt.Println("识别成功：" + result)
        } else {
            fmt.Println("识别失败，HTTP StatusCode: " + strconv.Itoa(statusCode))
        }
        
}

func main() {

    var appkey string = "填入appkey"
        var token  string = "填入token"
        
        var fileName string = "nls-sample-16k.wav"
        var format string = "pcm"
        var sampleRate int = 16000
        var enablePunctuationPrediction bool = true
        var enableInverseTextNormalization bool = true
        var enableVoiceDetection = false
        
        process(appkey, token, fileName, format, sampleRate, enablePunctuationPrediction, enableInverseTextNormalization, enableVoiceDetection)
        

}
```