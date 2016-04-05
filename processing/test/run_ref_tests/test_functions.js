// Tolerance values and kernel for blur
var sigma = 2; // radius
var epsilon = 0.05; // match accuracy ~5%
var kernel, kernelSize;
var results;
var tl;
buildKernel();

var passedCount = 0,
    failedCount = 0,
    failedCount3D = 0,
    knownFailedCount = 0;

function prepareResults(testsLength) {
  console.log("preparing results")
  results = document.createElement('div');
  results.className = 'results';
  document.body.appendChild(results);
  tl = testsLength;
}

function stillRunningTests() {
  console.log( "finished yet?" );
  var finishedCount = passedCount + failedCount + failedCount3D + knownFailedCount;
  console.log("tl: " + tl + " fin: " + finishedCount);
  return finishedCount !== tl;
}

/**
 *
 */
var isWebGLBrowser = (function() {
  var canvas = document.createElement('canvas'),
  contextNames = ["webgl", "experimental-webgl", "moz-webgl", "webkit-3d"],
  i = contextNames.length,
  ctx;

  while (i--) {
    try {
      ctx = canvas.getContext(contextNames[i]);
      if (ctx) {
        return true;
      }
    } catch(e) {}
  }
  return false;
})();

/**
 *
 */
var buildCanvas = function(id, w, h) {
  var c = document.createElement('canvas');
  c.id = id;
  c.width = w;
  c.height = h;
  c.className = "test";
  return c;
};

/**
 *
 */
var blur = function(data, width, height) {
  var len = data.length;
  var newData = new Array(len);

  for (var y = 0; y < height; ++y) {
    for (var x = 0; x < width; ++x) {
      var r = 0, g = 0, b = 0, a = 0, sum = 0;
      var j = Math.max(1 - kernelSize, -y), jabs = -j;
      for (; j < kernelSize; jabs = Math.abs(++j)) {
        if(y + j >= height) { break; }
        var i = Math.max(1 - kernelSize, -x), iabs = -i;
        var offset = 4 * ((y + j) * width + (x + i));
        for (; i < kernelSize; iabs = Math.abs(++i)) {
          if (x + i >= width) { break; }
          var k = kernel[jabs][iabs];
          r += data[offset++] * k;
          g += data[offset++] * k;
          b += data[offset++] * k;
          a += data[offset++] * k;
          sum += k;
        }
      }
      var destOffset = 4 * (y * width + x);
      newData[destOffset++] = r / sum;
      newData[destOffset++] = g / sum;
      newData[destOffset++] = b / sum;
      newData[destOffset++] = a / sum;
    }
  }
  return newData;
}

/**
 *
 */
var titleText = function(testNumber, testTotal, time, testName, hasFailed, message) {
  return "Test (" + testNumber + "/" + testTotal + ") [" + time + "ms]: " + testName +
    (hasFailed ? " -- FAILED (" + message + ")" : message ? " -- PASSED (" + message + ")" : " -- PASSED ");
};

/**
 *
 */
var getPixels = function(aCanvas, isWebGL) {
  try {
    if (isWebGL) {
      var context = aCanvas.getContext("experimental-webgl");
      var data = null;
      try{
        // try deprecated way first
        data = context.readPixels(0, 0, aCanvas.width, aCanvas.height, context.RGBA, context.UNSIGNED_BYTE);

        // Chrome posts an error
        if(context.getError()){
          throw new Error("readPixels() API has changed.");
        }
      }catch(e){
        // if that failed, try new way
        if(!data){
          data = new Uint8Array(aCanvas.width * aCanvas.height * 4);
          context.readPixels(0, 0, aCanvas.width, aCanvas.height, context.RGBA, context.UNSIGNED_BYTE, data);
        }
      }
      return data;
    } else {
      var x =  aCanvas.getContext('2d').getImageData(0, 0, aCanvas.width, aCanvas.height).data;
      console.log(x[0]);
      return aCanvas.getContext('2d').getImageData(0, 0, aCanvas.width, aCanvas.height).data;
    }
  } catch (e) {
    console.log(e);
    return null;
  }
};

/**
 *
 */
function runTest(test) {
  console.log(">>>> RUNNING TEST " + test.name + " " + test.index);
  var id = test.id + "_" + test.index;
  var index = test.index;
  var result = document.createElement('div');
  result.id = id;
  var title = document.createElement('div');
  title.className = "title";
  result.appendChild(title);
  result.className = "result";
  results.appendChild(result);
  var valueEpsilon = epsilon * 255;

  if (test.epsilonOverride && test.epsilonOverride > epsilon) {
    valueEpsilon = test.epsilonOverride * 255;
  }

  var original = buildCanvas(test.name + '-original', test.width, test.height);
  var current  = buildCanvas(test.name + '-current', test.width, test.height);
  var diff     = buildCanvas(test.name + '-diff', test.width, test.height);

  result.appendChild(original);
  result.appendChild(current);
  result.appendChild(diff);

  var pixelsLen = test.pixels.length;

  function validateResult() {
    console.log('============================================== VALIDATING');
    var is3D = test.is3D;
    totalTime = Date.now() - startTime;

    // draw the original based on stored pixels
    var origCtx = original.getContext('2d');
    var origData = origCtx.createImageData(original.width, original.height);
    for (var l=0; l < pixelsLen; l++) {
      if (is3D) {
        // WebGL inverts the rows in readPixels vs. the 2D context. Flip the image around so it looks right
        origData.data[l] = test.pixels[(original.height - 1 - Math.floor(l / 4 / original.width)) *
          original.width * 4 + (l % (original.width * 4))];
      } else {
        origData.data[l] = test.pixels[l];
      }
    }
    origCtx.putImageData(origData, 0, 0);

    // Blur pixels for diff
    test.pixels = blur(test.pixels, test.width, test.height);

    // do a visual diff on the pixels
    var currData = getPixels(current, is3D);
    if (!currData) {
      title.innerHTML = titleText(id, tl, totalTime, test.name, true, "can't diff pixels");
      result.className = "failed";
      failedCount++;
    }

    if (currData.length == test.pixels.length) {
      currData = blur(currData, test.width, test.height);
      var diffCtx = diff.getContext('2d');
      var diffData = diffCtx.createImageData(current.width, current.height);
      var tp = test.pixels;
      var failed = false;
      for (var j=0; j < pixelsLen;) {
        //console.log("CurrData " + currData[j] + ", " + currData[j+1] + ", " + currData[j+2] + ", " + currData[j+3]);
        //console.log("TestData " + tp[j] + ", " + tp[j+1] + ", " + tp[j+2] + ", " + tp[j+3]);
        //console.log("Diff " + Math.abs(currData[j] - tp[j]) + ", " + Math.abs(currData[j+1] - tp[j+1]) + ", " + Math.abs(currData[j+2] - tp[j+2]) + ", " + Math.abs(currData[j+3] -tp[j+3]));
        if (Math.abs(currData[j] - tp[j]) < valueEpsilon  &&
            Math.abs(currData[j + 1] - tp[j + 1]) < valueEpsilon &&
            Math.abs(currData[j + 2] - tp[j + 2]) < valueEpsilon &&
              Math.abs(currData[j + 3] - tp[j + 3]) < valueEpsilon)  {
                diffData.data[j] = diffData.data[j+1] = diffData.data[j+2] = diffData.data[j+3] = 0;
              } else {
                console.log("<------------------------------------------------------------------------------------- THAT DIDN'T WORK!");
                console.log("valueEpsilon " + valueEpsilon);
                console.log("  " + Math.abs(currData[j] - tp[j]) + " " + Math.abs(currData[j+1] - tp[j+1]) + " " + Math.abs(currData[j+2] - tp[j+2]) + " " + Math.abs(currData[j+3] - tp[j+3]));
                diffData.data[j] = 255;
                diffData.data[j+1] = diffData.data[j+2] = 0;
                diffData.data[j+3] = 255;
                failed = true;
              }
              j+=4;
      }
      if (failed && is3D) {
        var w = 4 * test.width, offset1 = 0, offset2 = diffData.data.length - w;
        while(offset1 < offset2) {
          for(var col=0; col<w; col++) {
            var tmp = diffData.data[offset1];
            diffData.data[offset1] = diffData.data[offset2];
            diffData.data[offset2] = tmp;
            offset1++;
            offset2++;
          }
          offset2 -= w + w;
        }
      }
      if (test.knownFailureTicket) {
        diffCtx.putImageData(diffData, 0, 0);
        title.innerHTML = titleText(id, tl, totalTime, test.name, true, "Known failure. See <a href='https://processing-js.lighthouseapp.com/projects/41284/tickets/" + test.knownFailureTicket + "'>ticket #" + test.knownFailureTicket + "</a>");
        result.className = "knownfailure";
        knownFailedCount++;
        result.click();
      } else if (failed) {
        diffCtx.putImageData(diffData, 0, 0);
        title.innerHTML = titleText(id, tl, totalTime, test.name, true, "pixels off");
        result.className = "failed";
        failedCount++;
        result.click();
      } else {
        diffCtx.fillStyle = "rgb(0,255,0)";
        diffCtx.fillRect (0, 0, diff.width, diff.height);
        if (test.epsilonOverride && test.epsilonOverride > epsilon) {
          title.innerHTML = titleText(id, tl, totalTime, test.name, false, "epsilonOverride = " + test.epsilonOverride);
        } else {
          title.innerHTML = titleText(id, tl, totalTime, test.name, false, "");
        }
        console.log("IT FUCKIN PASSED!!!!");
        result.className = "passed";
        passedCount++;
        result.click();
      }
    } else {
      title.innerHTML = titleText(id, tl, totalTime, test.name, true, "size mismatch");
      result.className = "failed";
      failedCount++;
      result.click();
    }
  }

  if (test.is3D && !isWebGLBrowser) {
    title.innerHTML = titleText(id, tl, 0, test.name, true, "Processing failed: WebGL context is not supported on this browser.");
    result.className = "failed-3D";
    failedCount3D++;
    result.click();
  } else {
    // draw the current version from code, timing it
    var startTime = Date.now(), totalTime = 0;
    var p, s;
    try {
      console.log('----------------------------------------------------------------------------------------------------------------');
      console.log(test.name);
var xhttp = new XMLHttpRequest();
xhttp.onreadystatechange = function() {
  if (xhttp.readyState === 4 && xhttp.status === 0) {
    var testPde = xhttp.responseText;
    if (testPde.length !== 0) {
      console.log(' ----- GOT ' + test.name);
      s = Processing.compile(testPde);
      console.log('Sketch from pde ' + Object.keys(s));
      console.log('Sketch from pde ' + s.sourceCode);
      console.log('Sketch from pde ' + Object.keys(s.imageCache.images));
      console.log('Sketch from pde ' + s.imageCache.pending);
      try {
      console.log('Sketch from pde ' + s.imageCache.images['house.png'].src);
      } catch (e) {
//nooop
      }
      s.sourceCode = test.testFunction;
      s.onExit = validateResult;

      p = new Processing(current, s);

    } else {
      console.log('Could not find file ' + test.name);
    }
  }};
xhttp.open("GET", test.name, true);
xhttp.send();



//client.onreadystatechange = function() {
  //if(client.readyState === 4 && client.status == 200) {
  //console.log('GOT THE PDE');
  //console.log(client.readyState);
  //console.log(client.status);
  //console.log(client.responseText);
      //// Wrap function with default sketch parameters
      //sketch = new Processing.Sketch(test.testFunction);
      //sketch.onExit = validateResult;
      //p = new Processing(current, sketch);
  //} else {
    //console.log('Oh oooh ' + client.status);
  //}
//}



    } catch (e) {
      console.log('FUCK! something went badly wrong! ' + e + '<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<');
      if (test.knownFailureTicket) {
        title.innerHTML = titleText(id, tl, 0, test.name, true, "Processing failed: " + e.toString() + ". Known failure. See <a href='https://processing-js.lighthouseapp.com/projects/41284/tickets/" + test.knownFailureTicket + "'>ticket #" + test.knownFailureTicket + "</a>");
        result.className = "knownfailure";
        knownFailedCount++;
        result.click();
      } else {
        title.innerHTML = titleText(id, tl, 0, test.name, true, "Processing failed: " + e.toString());
        result.className = "failed";
        failedCount++;
        result.click();
      }
    }
  }
}

/**
 *
 */
function buildKernel() {
  var ss = sigma * sigma;
  var factor = 2 * Math.PI * ss;
  kernel = new Array();
  kernel.push(new Array());
  var i = 0, j;
  do {
    var g = Math.exp(-(i * i) / (2 * ss)) / factor;
    if (g < 1e-3) break;
    kernel[0].push(g);
    ++i;
  } while (i < 7);
  kernelSize = i;
  for (j = 1; j < kernelSize; ++j) {
    kernel.push(new Array());
    for (i = 0; i < kernelSize; ++i) {
      var g = Math.exp(-(i * i + j * j) / (2 * ss)) / factor;
      kernel[j].push(g);
    }
  }
}

